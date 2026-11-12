package com.mentora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Persistent student-mentor chat. One conversation per student, with the student's currently assigned mentor. */
@Service
public class MessageService {
  public record MessageSaved(Long messageId) {}
  public record MessageDto(Long id, Long studentId, Long senderId, String senderRole, String senderName, String content, String attachmentName,
                           boolean hasAttachment, Instant createdAt, Instant readAt, String status, JsonNode aiSummary, String aiStatus) {}
  public record ConversationDto(Long studentId, String name, String usn, String lastMessage, Instant lastAt, long unread, boolean lastFromStudent) {}
  public record ReadReceipt(Long studentId, Instant readAt, Long readerId) {}

  private final MessageRepository messages;
  private final UserRepository users;
  private final StudentRepository students;
  private final AccessService access;
  private final ModerationService moderation;
  private final NotificationService notifications;
  private final FileStorageService storage;
  private final SimpMessagingTemplate ws;
  private final ApplicationEventPublisher events;
  private final ObjectMapper mapper;

  public MessageService(MessageRepository messages, UserRepository users, StudentRepository students, AccessService access, ModerationService moderation,
                        NotificationService notifications, FileStorageService storage, SimpMessagingTemplate ws, ApplicationEventPublisher events, ObjectMapper mapper) {
    this.messages = messages; this.users = users; this.students = students; this.access = access; this.moderation = moderation;
    this.notifications = notifications; this.storage = storage; this.ws = ws; this.events = events; this.mapper = mapper;
  }

  @Transactional
  public MessageDto send(UserPrincipal p, Long studentId, String content, MultipartFile attachment) {
    if (p.role() == Role.ADMIN) throw ApiException.forbidden("Admins cannot send mentoring messages");
    Student s = access.requireStudentAccess(p, studentId);
    Faculty mentor = access.mentorOf(studentId);
    if (mentor == null) throw ApiException.bad("No mentor is assigned to this student yet");
    String text = content == null ? "" : content.trim();
    if (text.isEmpty() && (attachment == null || attachment.isEmpty())) throw ApiException.bad("Message cannot be empty");
    if (!text.isEmpty() || attachment == null) {
      var v = moderation.check(p.id(), text);
      if (!v.allowed()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, v.message());
    }
    Message m = new Message();
    m.setStudentId(studentId); m.setSenderId(p.id()); m.setContent(text.isEmpty() ? "(attachment)" : text);
    if (attachment != null && !attachment.isEmpty()) {
      var st = storage.store("messages/" + studentId, attachment);
      m.setAttachmentName(st.originalName()); m.setAttachmentPath(st.path()); m.setAttachmentType(st.contentType());
    }
    if (p.role() == Role.STUDENT) m.setAiStatus("PENDING");
    messages.save(m);
    User sender = users.findById(p.id()).orElseThrow();
    Long recipient = p.role() == Role.STUDENT ? mentor.getUserId() : s.getUserId();
    if (p.role() == Role.STUDENT) {
      notifications.notify(recipient, NotificationType.NEW_MESSAGE, "New message from " + sender.getFullName(), preview(m.getContent()), "/mentor/messages?student=" + studentId, null);
    } else {
      notifications.notify(recipient, NotificationType.MENTOR_REPLY, "Reply from your mentor", preview(m.getContent()), "/student/messages", null);
    }
    events.publishEvent(new MessageSaved(m.getId()));
    MessageDto forSender = dto(m, sender, p, sender.getFullName());
    pushTo(s.getUserId(), m, p.role() == Role.STUDENT ? Role.STUDENT : Role.MENTOR, sender);
    pushTo(mentor.getUserId(), m, p.role() == Role.STUDENT ? Role.STUDENT : Role.MENTOR, sender);
    return forSender;
  }

  private void pushTo(Long userId, Message m, Role senderRole, User sender) {
    try {
      // The mentor receives the (initially empty) AI summary field; it is delivered separately once generated.
      MessageDto d = new MessageDto(m.getId(), m.getStudentId(), m.getSenderId(), senderRole.name(), sender.getFullName(), m.getContent(), m.getAttachmentName(),
          m.getAttachmentPath() != null, m.getCreatedAt(), m.getReadAt(), "SENT", null, m.getAiStatus());
      ws.convertAndSendToUser(String.valueOf(userId), "/queue/messages", d);
    } catch (Exception ignored) { /* recipient offline; history is persisted */ }
  }

  @Transactional(readOnly = true)
  public List<MessageDto> history(UserPrincipal p, Long studentId, int page, int size) {
    access.requireStudentAccess(p, studentId);
    var pg = messages.findByStudentId(studentId, PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
    List<Message> list = new ArrayList<>(pg.getContent());
    Collections.reverse(list);
    Set<Long> ids = list.stream().map(Message::getSenderId).collect(Collectors.toSet());
    Map<Long, User> um = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    return list.stream().map(m -> dto(m, um.get(m.getSenderId()), p, null)).toList();
  }

  private MessageDto dto(Message m, User sender, UserPrincipal viewer, String nameOverride) {
    String role = sender == null ? "?" : sender.getRole().name();
    JsonNode summary = null;
    // AI summaries are only visible to the mentor, and only for student-authored messages
    if (viewer.role() == Role.MENTOR && "STUDENT".equals(role) && m.getAiSummary() != null) {
      try { summary = mapper.readTree(m.getAiSummary()); } catch (Exception ignored) { }
    }
    return new MessageDto(m.getId(), m.getStudentId(), m.getSenderId(), role, nameOverride != null ? nameOverride : sender == null ? "?" : sender.getFullName(),
        m.getContent(), m.getAttachmentName(), m.getAttachmentPath() != null, m.getCreatedAt(), m.getReadAt(), m.getReadAt() != null ? "READ" : "DELIVERED",
        summary, viewer.role() == Role.MENTOR && "STUDENT".equals(role) ? m.getAiStatus() : null);
  }

  @Transactional
  public void markRead(UserPrincipal p, Long studentId) {
    Student s = access.requireStudentAccess(p, studentId);
    List<Message> unread = messages.unreadFor(studentId, p.id());
    if (unread.isEmpty()) return;
    Instant now = Instant.now();
    unread.forEach(m -> m.setReadAt(now));
    Faculty mentor = access.mentorOf(studentId);
    Long other = p.role() == Role.STUDENT ? (mentor == null ? null : mentor.getUserId()) : s.getUserId();
    if (other != null) {
      try { ws.convertAndSendToUser(String.valueOf(other), "/queue/read", new ReadReceipt(studentId, now, p.id())); } catch (Exception ignored) { }
    }
  }

  @Transactional(readOnly = true)
  public List<ConversationDto> conversations(Faculty f, UserPrincipal p) {
    List<Long> ids = access.assignedStudentIds(f);
    if (ids.isEmpty()) return List.of();
    Map<Long, Student> st = students.findByIdIn(ids).stream().collect(Collectors.toMap(Student::getId, Function.identity()));
    Map<Long, User> um = users.findAllById(st.values().stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    Map<Long, Message> last = messages.lastMessages(ids).stream().collect(Collectors.toMap(Message::getStudentId, Function.identity()));
    Map<Long, Long> unread = new HashMap<>();
    messages.unreadByStudent(ids, p.id()).forEach(r -> unread.put((Long) r[0], (Long) r[1]));
    List<ConversationDto> out = new ArrayList<>();
    for (Student s : st.values()) {
      Message m = last.get(s.getId());
      out.add(new ConversationDto(s.getId(), um.get(s.getUserId()).getFullName(), s.getUsn(), m == null ? null : preview(m.getContent()), m == null ? null : m.getCreatedAt(),
          unread.getOrDefault(s.getId(), 0L), m != null && !m.getSenderId().equals(p.id())));
    }
    out.sort(Comparator.comparing((ConversationDto c) -> c.lastAt() == null ? Instant.EPOCH : c.lastAt()).reversed().thenComparing(ConversationDto::usn));
    return out;
  }

  public long unreadCount(UserPrincipal p) {
    if (p.role() == Role.STUDENT) return messages.countUnread(List.of(access.studentOf(p).getId()), p.id());
    if (p.role() == Role.MENTOR) { var ids = access.assignedStudentIds(access.facultyOf(p)); return ids.isEmpty() ? 0 : messages.countUnread(ids, p.id()); }
    return 0;
  }

  @Transactional(readOnly = true)
  public Message authorizedAttachment(UserPrincipal p, Long messageId) {
    Message m = messages.findById(messageId).orElseThrow(() -> ApiException.notFound("Not found"));
    access.requireStudentAccess(p, m.getStudentId());
    if (m.getAttachmentPath() == null) throw ApiException.notFound("Not found");
    return m;
  }

  private static String preview(String s) { return s.length() > 80 ? s.substring(0, 77) + "..." : s; }
}
