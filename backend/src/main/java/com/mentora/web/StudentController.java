package com.mentora.web;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Student-scoped read models. Every handler authorizes through AccessService (own record for students, assigned students for mentors). */
@RestController
@RequestMapping("/api")
public class StudentController {
  public record NoteRequest(@NotBlank @Size(max = 2000) String content) {}
  public record NoteDto(Long id, String content, Instant createdAt, String author) {}

  private final AccessService access;
  private final StudentDataService data;
  private final AnalyticsService analytics;
  private final LeaveService leaveService;
  private final MentorFeedbackRepository feedback;
  private final AcademicObservationRepository observations;
  private final NotificationService notifications;
  private final NotificationRepository notificationRepo;
  private final MessageService messageService;
  private final SubjectRepository subjects;
  private final StudentRepository students;
  private final UserRepository users;
  private final FacultyRepository faculty;

  public StudentController(AccessService access, StudentDataService data, AnalyticsService analytics, LeaveService leaveService, MentorFeedbackRepository feedback,
                           AcademicObservationRepository observations, NotificationService notifications, NotificationRepository notificationRepo, MessageService messageService,
                           SubjectRepository subjects, StudentRepository students, UserRepository users, FacultyRepository faculty) {
    this.access = access; this.data = data; this.analytics = analytics; this.leaveService = leaveService; this.feedback = feedback; this.observations = observations;
    this.notifications = notifications; this.notificationRepo = notificationRepo; this.messageService = messageService; this.subjects = subjects;
    this.students = students; this.users = users; this.faculty = faculty;
  }

  @GetMapping("/students/{id}/profile")
  public Map<String, Object> profile(@PathVariable Long id) { access.requireStudentAccess(access.current(), id); return data.profile(id); }

  @GetMapping("/students/{id}/attendance")
  public Map<String, Object> attendance(@PathVariable Long id, @RequestParam(required = false) String month, @RequestParam(required = false) Long subjectId) {
    access.requireStudentAccess(access.current(), id);
    try { return data.attendanceView(id, month, subjectId); } catch (java.time.format.DateTimeParseException e) { throw ApiException.bad("Month must be yyyy-MM"); }
  }

  @GetMapping("/students/{id}/marks")
  public Map<String, Object> marks(@PathVariable Long id) { access.requireStudentAccess(access.current(), id); return data.marksView(id); }

  @GetMapping("/students/{id}/results")
  public Object results(@PathVariable Long id) { access.requireStudentAccess(access.current(), id); return data.results(id); }

  @GetMapping("/students/{id}/analytics")
  public Map<String, Object> analytics(@PathVariable Long id) { access.requireStudentAccess(access.current(), id); return data.analytics(id); }

  @GetMapping("/students/{id}/leaves")
  public List<LeaveService.LeaveDto> leaves(@PathVariable Long id) { access.requireStudentAccess(access.current(), id); return leaveService.forStudent(id); }

  // ----- feedback (visible to the student) -----
  @GetMapping("/students/{id}/feedback")
  public List<NoteDto> feedback(@PathVariable Long id) {
    access.requireStudentAccess(access.current(), id);
    return feedback.findByStudentIdOrderByCreatedAtDesc(id).stream().map(f -> new NoteDto(f.getId(), f.getContent(), f.getCreatedAt(), facultyName(f.getFacultyId()))).toList();
  }

  @PostMapping("/students/{id}/feedback")
  @Transactional
  public NoteDto addFeedback(@PathVariable Long id, @Valid @RequestBody NoteRequest r) {
    UserPrincipal p = access.current();
    Student s = access.requireStudentAccess(p, id);
    Faculty f = access.facultyOf(p);
    MentorFeedback fb = new MentorFeedback();
    fb.setStudentId(id); fb.setFacultyId(f.getId()); fb.setContent(r.content().trim());
    feedback.save(fb);
    notifications.notify(s.getUserId(), NotificationType.FEEDBACK, "New feedback from your mentor", fb.getContent().length() > 120 ? fb.getContent().substring(0, 117) + "..." : fb.getContent(), "/student/dashboard", null);
    return new NoteDto(fb.getId(), fb.getContent(), fb.getCreatedAt(), facultyName(f.getId()));
  }

  // ----- private observations (mentor only, never exposed to the student) -----
  @GetMapping("/students/{id}/observations")
  public List<NoteDto> observations(@PathVariable Long id) {
    UserPrincipal p = access.current();
    access.requireStudentAccess(p, id);
    if (p.role() != Role.MENTOR) throw ApiException.forbidden("Access denied");
    return observations.findByStudentIdOrderByCreatedAtDesc(id).stream().map(o -> new NoteDto(o.getId(), o.getContent(), o.getCreatedAt(), facultyName(o.getFacultyId()))).toList();
  }

  @PostMapping("/students/{id}/observations")
  @Transactional
  public NoteDto addObservation(@PathVariable Long id, @Valid @RequestBody NoteRequest r) {
    UserPrincipal p = access.current();
    access.requireStudentAccess(p, id);
    Faculty f = access.facultyOf(p);
    AcademicObservation o = new AcademicObservation();
    o.setStudentId(id); o.setFacultyId(f.getId()); o.setContent(r.content().trim());
    observations.save(o);
    return new NoteDto(o.getId(), o.getContent(), o.getCreatedAt(), facultyName(f.getId()));
  }

  private String facultyName(Long facultyId) {
    return faculty.findById(facultyId).flatMap(f -> users.findById(f.getUserId())).map(User::getFullName).orElse("Mentor");
  }

  // ----- student dashboard -----
  @GetMapping("/student/dashboard")
  public Map<String, Object> dashboard() {
    UserPrincipal p = access.current();
    Student s = access.studentOf(p);
    Map<String, Object> out = new LinkedHashMap<>(data.profile(s.getId()));
    var m = analytics.metrics(List.of(s.getId())).get(0);
    out.put("latestIa", m.ia3() != null ? m.ia3() : m.ia2() != null ? m.ia2() : m.ia1());
    out.put("latestIaLabel", m.ia3() != null ? "IA 3" : m.ia2() != null ? "IA 2" : m.ia1() != null ? "IA 1" : null);
    out.put("totalSubjects", subjects.findBySemesterOrderByCode(s.getSemester()).size());
    out.put("pendingLeaves", m.pendingLeaves());
    out.put("unreadMessages", messageService.unreadCount(p));
    out.put("recentNotifications", notifications.list(p.id(), 0, 6));
    out.put("insights", ((List<?>) data.analytics(s.getId()).get("insights")).stream().limit(4).toList());
    out.put("attendanceThreshold", data.attendanceView(s.getId(), null, null).get("threshold"));
    return out;
  }
}
