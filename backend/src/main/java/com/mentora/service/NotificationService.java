package com.mentora.service;

import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NotificationService {
  public record NotificationDto(Long id, NotificationType type, String title, String description, String link, boolean read, Instant createdAt,
                                String priority, Long studentId, String studentName, String usn) {}

  private final NotificationRepository repo;
  private final SimpMessagingTemplate ws;
  private final StudentRepository students;
  private final UserRepository users;

  public NotificationService(NotificationRepository repo, SimpMessagingTemplate ws, StudentRepository students, UserRepository users) {
    this.repo = repo; this.ws = ws; this.students = students; this.users = users;
  }

  private List<NotificationDto> dtos(Collection<Notification> list) {
    Set<Long> sids = list.stream().map(Notification::getStudentId).filter(Objects::nonNull).collect(Collectors.toSet());
    Map<Long, Student> st = students.findByIdIn(sids).stream().collect(Collectors.toMap(Student::getId, s -> s));
    Map<Long, String> names = users.findAllById(st.values().stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, User::getFullName));
    return list.stream().map(n -> {
      Student s = n.getStudentId() == null ? null : st.get(n.getStudentId());
      return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getDescription(), n.getLink(), n.isRead(), n.getCreatedAt(), n.getPriority(),
          n.getStudentId(), s == null ? null : names.get(s.getUserId()), s == null ? null : s.getUsn());
    }).toList();
  }

  /** Persist and push in real time. Returns false if an identical event (same dedupeKey) was already notified. */
  @Transactional
  public boolean notify(Long userId, NotificationType type, String title, String description, String link, String dedupeKey) {
    return notify(userId, type, title, description, link, dedupeKey, "NORMAL", null);
  }

  @Transactional
  public boolean notify(Long userId, NotificationType type, String title, String description, String link, String dedupeKey, String priority, Long studentId) {
    if (dedupeKey != null && repo.existsByUserIdAndDedupeKey(userId, dedupeKey)) return false;
    Notification n = new Notification();
    n.setUserId(userId); n.setType(type); n.setTitle(title); n.setDescription(description); n.setLink(link); n.setDedupeKey(dedupeKey);
    n.setPriority(priority == null ? "NORMAL" : priority); n.setStudentId(studentId);
    repo.save(n);
    try { ws.convertAndSendToUser(String.valueOf(userId), "/queue/notifications", dtos(List.of(n)).get(0)); } catch (Exception ignored) { /* user offline */ }
    return true;
  }

  public List<NotificationDto> list(Long userId, int page, int size) { return list(userId, page, size, false); }

  public List<NotificationDto> list(Long userId, int page, int size, boolean unreadOnly) {
    var pr = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
    var p = unreadOnly ? repo.findByUserIdAndReadFalse(userId, pr) : repo.findByUserId(userId, pr);
    return dtos(p.getContent());
  }

  public long unread(Long userId) { return repo.countByUserIdAndReadFalse(userId); }

  @Transactional
  public void markRead(Long userId, Long id) {
    Notification n = repo.findById(id).orElseThrow(() -> ApiException.notFound("Notification not found"));
    if (!n.getUserId().equals(userId)) throw ApiException.forbidden("Access denied");
    n.setRead(true);
  }

  @Transactional
  public void markAllRead(Long userId) { repo.markAllRead(userId); }
}
