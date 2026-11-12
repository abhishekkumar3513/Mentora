package com.mentora.service;

import com.mentora.domain.Notification;
import com.mentora.domain.NotificationType;
import com.mentora.repo.NotificationRepository;
import com.mentora.web.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class NotificationService {
  public record NotificationDto(Long id, NotificationType type, String title, String description, String link, boolean read, Instant createdAt) {}

  private final NotificationRepository repo;
  private final SimpMessagingTemplate ws;

  public NotificationService(NotificationRepository repo, SimpMessagingTemplate ws) { this.repo = repo; this.ws = ws; }

  public static NotificationDto dto(Notification n) {
    return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getDescription(), n.getLink(), n.isRead(), n.getCreatedAt());
  }

  /** Persist and push in real time. Returns false if an identical event (same dedupeKey) was already notified. */
  @Transactional
  public boolean notify(Long userId, NotificationType type, String title, String description, String link, String dedupeKey) {
    if (dedupeKey != null && repo.existsByUserIdAndDedupeKey(userId, dedupeKey)) return false;
    Notification n = new Notification();
    n.setUserId(userId); n.setType(type); n.setTitle(title); n.setDescription(description); n.setLink(link); n.setDedupeKey(dedupeKey);
    repo.save(n);
    try { ws.convertAndSendToUser(String.valueOf(userId), "/queue/notifications", dto(n)); } catch (Exception ignored) { /* user offline */ }
    return true;
  }

  public List<NotificationDto> list(Long userId, int page, int size) {
    return repo.findByUserId(userId, PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"))).map(NotificationService::dto).getContent();
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
