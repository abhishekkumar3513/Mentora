package com.mentora.service;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Mentor self-service: manage the mentor's own roster and edit attendance of their own students. */
@Service
public class RosterService {
  public record AvailableStudent(Long studentId, String name, String usn, int semester) {}
  public record DayEntry(Long studentId, String name, String usn, String status) {}
  public record MarkEntry(Long studentId, AttStatus status) {}
  public record MarkResult(int updated, int alertsCreated) {}

  private static final int MAX_BACKDATE_DAYS = 60;

  private final AccessService access;
  private final StudentRepository students;
  private final UserRepository users;
  private final MentorAssignmentRepository assignments;
  private final AttendanceRepository attendance;
  private final AttendanceCalc calc;
  private final ImportService importer;
  private final NotificationService notifications;

  public RosterService(AccessService access, StudentRepository students, UserRepository users, MentorAssignmentRepository assignments,
                       AttendanceRepository attendance, AttendanceCalc calc, ImportService importer, NotificationService notifications) {
    this.access = access; this.students = students; this.users = users; this.assignments = assignments; this.attendance = attendance;
    this.calc = calc; this.importer = importer; this.notifications = notifications;
  }

  /** Students that currently have no mentor (the only ones a mentor may take on). */
  @Transactional(readOnly = true)
  public List<AvailableStudent> available(String search) {
    Set<Long> assigned = assignments.findByActiveTrue().stream().map(MentorAssignment::getStudentId).collect(Collectors.toSet());
    String q = search == null ? "" : search.trim().toLowerCase();
    List<Student> free = students.findAll().stream().filter(s -> !assigned.contains(s.getId())).toList();
    Map<Long, User> um = users.findAllById(free.stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    return free.stream().map(s -> new AvailableStudent(s.getId(), um.get(s.getUserId()).getFullName(), s.getUsn(), s.getSemester()))
        .filter(a -> q.isEmpty() || a.name().toLowerCase().contains(q) || a.usn().toLowerCase().contains(q))
        .sorted(Comparator.comparing(AvailableStudent::usn)).limit(50).toList();
  }

  @Transactional
  public AvailableStudent add(UserPrincipal p, String usn) {
    Faculty f = access.facultyOf(p);
    if (usn == null || usn.isBlank()) throw ApiException.bad("USN is required");
    Student s = students.findByUsnIgnoreCase(usn.trim()).orElseThrow(() -> ApiException.bad("No student with USN " + usn.trim()));
    var current = assignments.findByStudentIdAndActiveTrue(s.getId());
    if (current.isPresent()) {
      if (current.get().getFacultyId().equals(f.getId())) throw ApiException.bad("This student is already in your list");
      throw ApiException.bad("This student is already assigned to another mentor. Ask the administrator to reassign them.");
    }
    MentorAssignment a = new MentorAssignment();
    a.setStudentId(s.getId()); a.setFacultyId(f.getId());
    assignments.save(a);
    User su = users.findById(s.getUserId()).orElseThrow();
    String mentorName = users.findById(p.id()).map(User::getFullName).orElse("your mentor");
    notifications.notify(s.getUserId(), NotificationType.ANNOUNCEMENT, "Mentor assigned", mentorName + " is now your mentor.", "/student/messages", null);
    return new AvailableStudent(s.getId(), su.getFullName(), s.getUsn(), s.getSemester());
  }

  /** Removes the student from this mentor (history is kept; the student becomes unassigned). */
  @Transactional
  public void remove(UserPrincipal p, Long studentId) {
    Faculty f = access.facultyOf(p);
    MentorAssignment a = assignments.findByStudentIdAndActiveTrue(studentId).filter(x -> x.getFacultyId().equals(f.getId()))
        .orElseThrow(() -> ApiException.forbidden("Access denied"));
    a.setActive(false);
    Student s = students.findById(studentId).orElseThrow();
    notifications.notify(s.getUserId(), NotificationType.ANNOUNCEMENT, "Mentor changed", "You are no longer assigned to your previous mentor. The administrator will assign a new mentor.", "/student/dashboard", null);
  }

  @Transactional(readOnly = true)
  public List<DayEntry> day(UserPrincipal p, LocalDate date) {
    List<Long> ids = access.assignedStudentIds(access.facultyOf(p));
    if (ids.isEmpty()) return List.of();
    Map<Long, List<Attendance>> by = attendance.findByStudentIdInAndDateBetween(ids, date, date).stream().collect(Collectors.groupingBy(Attendance::getStudentId));
    Map<Long, Student> st = students.findByIdIn(ids).stream().collect(Collectors.toMap(Student::getId, Function.identity()));
    Map<Long, User> um = users.findAllById(st.values().stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    List<DayEntry> out = new ArrayList<>();
    for (Student s : st.values()) {
      List<Attendance> recs = by.getOrDefault(s.getId(), List.of());
      out.add(new DayEntry(s.getId(), um.get(s.getUserId()).getFullName(), s.getUsn(), recs.isEmpty() ? "" : AttendanceCalc.dayStatus(recs).name()));
    }
    out.sort(Comparator.comparing(DayEntry::usn));
    return out;
  }

  /** All-or-nothing: every student must belong to the mentor, otherwise nothing is changed. */
  @Transactional
  public MarkResult mark(UserPrincipal p, LocalDate date, List<MarkEntry> entries) {
    Faculty f = access.facultyOf(p);
    if (date == null) throw ApiException.bad("Date is required");
    if (date.isAfter(LocalDate.now())) throw ApiException.bad("Attendance cannot be marked for a future date");
    if (date.isBefore(LocalDate.now().minusDays(MAX_BACKDATE_DAYS))) throw ApiException.bad("Attendance older than " + MAX_BACKDATE_DAYS + " days cannot be edited here; ask the administrator");
    if (entries == null || entries.isEmpty()) throw ApiException.bad("Nothing to save");
    Set<Long> mine = new HashSet<>(access.assignedStudentIds(f));
    for (MarkEntry e : entries) {
      if (e.studentId() == null || !mine.contains(e.studentId())) throw ApiException.forbidden("Access denied");
      if (e.status() == null) throw ApiException.bad("Status is required for every student");
    }
    int alertsCreated = 0;
    for (MarkEntry e : entries) {
      Student s = students.findById(e.studentId()).orElseThrow();
      alertsCreated += importer.markDayFor(s, date, e.status());
    }
    return new MarkResult(entries.size(), alertsCreated);
  }
}
