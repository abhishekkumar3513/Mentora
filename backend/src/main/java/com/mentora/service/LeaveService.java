package com.mentora.service;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LeaveService {
  public record DocDto(Long id, String fileName, String contentType, long size) {}
  public record AuditDto(String action, String actor, String comment, Instant at) {}
  public record LeaveDto(Long id, Long studentId, String studentName, String usn, LeaveType type, LocalDate startDate, LocalDate endDate,
                         String reason, LeaveStatus status, String reviewComment, Instant createdAt, Instant reviewedAt,
                         List<DocDto> documents, List<AuditDto> history) {}

  private final LeaveRequestRepository leaves;
  private final LeaveDocumentRepository docs;
  private final LeaveAuditRepository audit;
  private final StudentRepository students;
  private final UserRepository users;
  private final AttendanceRepository attendance;
  private final FileStorageService storage;
  private final AccessService access;
  private final NotificationService notifications;
  private final AttendanceAlertService alerts;

  public LeaveService(LeaveRequestRepository leaves, LeaveDocumentRepository docs, LeaveAuditRepository audit, StudentRepository students,
                      UserRepository users, AttendanceRepository attendance, FileStorageService storage, AccessService access,
                      NotificationService notifications, AttendanceAlertService alerts) {
    this.leaves = leaves; this.docs = docs; this.audit = audit; this.students = students; this.users = users; this.attendance = attendance;
    this.storage = storage; this.access = access; this.notifications = notifications; this.alerts = alerts;
  }

  /** Submitting NEVER changes attendance; only approval does. */
  @Transactional
  public LeaveDto submit(UserPrincipal p, LeaveType type, LocalDate start, LocalDate end, String reason, List<MultipartFile> files) {
    Student s = access.studentOf(p);
    if (start == null || end == null) throw ApiException.bad("Start and end date are required");
    if (end.isBefore(start)) throw ApiException.bad("End date must not be before start date");
    if (ChronoUnit.DAYS.between(start, end) > 60) throw ApiException.bad("Leave period cannot exceed 60 days");
    if (reason == null || reason.isBlank() || reason.length() > 2000) throw ApiException.bad("Reason is required (max 2000 characters)");
    List<MultipartFile> real = files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
    if (real.size() > 5) throw ApiException.bad("At most 5 documents per application");
    LeaveRequest l = new LeaveRequest();
    l.setStudentId(s.getId()); l.setType(type); l.setStartDate(start); l.setEndDate(end); l.setReason(reason.trim());
    leaves.save(l);
    for (MultipartFile f : real) {
      var st = storage.store("leaves/" + l.getId(), f);
      LeaveDocument d = new LeaveDocument();
      d.setLeaveId(l.getId()); d.setFileName(st.originalName()); d.setContentType(st.contentType()); d.setSize(st.size()); d.setStoragePath(st.path());
      docs.save(d);
    }
    audit(l.getId(), "SUBMITTED", p.id(), null);
    Faculty mentor = access.mentorOf(s.getId());
    if (mentor != null) {
      User u = users.findById(s.getUserId()).orElseThrow();
      notifications.notify(mentor.getUserId(), NotificationType.NEW_LEAVE, "New leave application",
          u.getFullName() + " (" + s.getUsn() + ") applied for " + type.name().toLowerCase().replace('_', ' ') + " leave from " + start + " to " + end + ".",
          "/mentor/leaves", "LEAVE_NEW:" + l.getId());
    }
    return toDtos(List.of(l)).get(0);
  }

  @Transactional
  public LeaveDto review(UserPrincipal p, Long leaveId, boolean approve, String comment) {
    LeaveRequest l = leaves.findById(leaveId).orElseThrow(() -> ApiException.notFound("Application not found"));
    Student s = access.requireStudentAccess(p, l.getStudentId());
    if (p.role() != Role.MENTOR) throw ApiException.forbidden("Only the assigned mentor can review");
    if (l.getStatus() != LeaveStatus.PENDING) throw ApiException.bad("Application already " + l.getStatus().name().toLowerCase());
    if (!approve && (comment == null || comment.isBlank())) throw ApiException.bad("A comment is required when rejecting");
    l.setStatus(approve ? LeaveStatus.APPROVED : LeaveStatus.REJECTED);
    l.setReviewComment(comment == null || comment.isBlank() ? null : comment.trim());
    l.setReviewedBy(p.id()); l.setReviewedAt(Instant.now());
    audit(l.getId(), approve ? "APPROVED" : "REJECTED", p.id(), l.getReviewComment());
    int updated = 0;
    if (approve) {
      updated = attendance.markLeave(s.getId(), l.getStartDate(), l.getEndDate());
      if (updated > 0) audit(l.getId(), "ATTENDANCE_UPDATED", p.id(), updated + " absent record(s) changed to approved leave (L)");
    }
    notifications.notify(s.getUserId(), approve ? NotificationType.LEAVE_APPROVED : NotificationType.LEAVE_REJECTED,
        approve ? "Leave application approved" : "Leave application rejected",
        "Your leave from " + l.getStartDate() + " to " + l.getEndDate() + " was " + (approve ? "approved" : "rejected")
            + (l.getReviewComment() == null ? "." : ": " + l.getReviewComment()), "/student/leaves", "LEAVE_REVIEW:" + l.getId());
    return toDtos(List.of(l)).get(0);
  }

  @Transactional(readOnly = true)
  public List<LeaveDto> forStudent(Long studentId) { return toDtos(leaves.findByStudentIdOrderByCreatedAtDesc(studentId)); }

  @Transactional(readOnly = true)
  public List<LeaveDto> forMentor(Faculty f, LeaveStatus status) {
    List<Long> ids = access.assignedStudentIds(f);
    if (ids.isEmpty()) return List.of();
    List<LeaveRequest> l = status == null ? leaves.findByStudentIdInOrderByCreatedAtDesc(ids)
        : leaves.findByStudentIdInAndStatus(ids, status).stream().sorted(Comparator.comparing(LeaveRequest::getCreatedAt).reversed()).toList();
    return toDtos(l);
  }

  /** Authorizes (owner student / assigned mentor / admin) before returning a document. */
  @Transactional(readOnly = true)
  public LeaveDocument authorizedDocument(UserPrincipal p, Long leaveId, Long docId) {
    LeaveRequest l = leaves.findById(leaveId).orElseThrow(() -> ApiException.notFound("Not found"));
    access.requireStudentAccess(p, l.getStudentId());
    LeaveDocument d = docs.findById(docId).orElseThrow(() -> ApiException.notFound("Not found"));
    if (!d.getLeaveId().equals(leaveId)) throw ApiException.notFound("Not found");
    return d;
  }

  private void audit(Long leaveId, String action, Long actor, String comment) {
    LeaveAudit a = new LeaveAudit();
    a.setLeaveId(leaveId); a.setAction(action); a.setActorId(actor); a.setComment(comment);
    audit.save(a);
  }

  private List<LeaveDto> toDtos(List<LeaveRequest> ls) {
    if (ls.isEmpty()) return List.of();
    Map<Long, Student> st = students.findByIdIn(ls.stream().map(LeaveRequest::getStudentId).collect(Collectors.toSet())).stream().collect(Collectors.toMap(Student::getId, Function.identity()));
    Map<Long, User> us = users.findAllById(st.values().stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    Map<Long, List<LeaveDocument>> dm = docs.findByLeaveIdIn(ls.stream().map(LeaveRequest::getId).toList()).stream().collect(Collectors.groupingBy(LeaveDocument::getLeaveId));
    List<LeaveDto> out = new ArrayList<>();
    for (LeaveRequest l : ls) {
      Student s = st.get(l.getStudentId());
      List<AuditDto> hist = audit.findByLeaveIdOrderByCreatedAt(l.getId()).stream()
          .map(a -> new AuditDto(a.getAction(), users.findById(a.getActorId()).map(User::getFullName).orElse("?"), a.getComment(), a.getCreatedAt())).toList();
      out.add(new LeaveDto(l.getId(), s.getId(), us.get(s.getUserId()).getFullName(), s.getUsn(), l.getType(), l.getStartDate(), l.getEndDate(), l.getReason(),
          l.getStatus(), l.getReviewComment(), l.getCreatedAt(), l.getReviewedAt(),
          dm.getOrDefault(l.getId(), List.of()).stream().map(d -> new DocDto(d.getId(), d.getFileName(), d.getContentType(), d.getSize())).toList(), hist));
    }
    return out;
  }
}
