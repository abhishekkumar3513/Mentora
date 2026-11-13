package com.mentora.service;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Student Support Navigator + mentor interventions. One table holds both student-raised requests and mentor-created
 * support actions; the event table is the timeline and carries the STUDENT / INTERNAL visibility split.
 * Confidential categories (personal counselling) are visible only to the student, administrators and counsellors/owners.
 */
@Service
public class SupportService {
  public static final List<String> STATUSES = List.of("SUBMITTED", "ASSIGNED", "IN_PROGRESS", "FOLLOW_UP_REQUIRED", "RESOLVED", "CLOSED");
  public static final List<String> OWNER_ROLES = List.of("FACULTY_MENTOR", "DEPARTMENT_HOD", "ACADEMIC_SUPPORT_TEAM", "CAREER_GUIDANCE_TEAM", "COUNSELLOR", "STUDENT_AFFAIRS");
  private static final Set<String> DONE = Set.of("RESOLVED", "CLOSED");
  private enum Level { FULL, MASKED, NONE }

  private final JdbcTemplate jdbc;
  private final AccessService access;
  private final StudentRepository students;
  private final UserRepository users;
  private final FacultyRepository faculty;
  private final NotificationService notifications;
  private final SettingsService settings;

  public SupportService(JdbcTemplate jdbc, AccessService access, StudentRepository students, UserRepository users, FacultyRepository faculty,
                        NotificationService notifications, SettingsService settings) {
    this.jdbc = jdbc; this.access = access; this.students = students; this.users = users; this.faculty = faculty; this.notifications = notifications; this.settings = settings;
  }

  // ------------------------------------------------------------------ scope / authorization

  /** Students the caller may see (mentor: assigned students, admin: everyone). */
  public List<Long> scope(UserPrincipal p) {
    return switch (p.role()) {
      case ADMIN -> students.findAll().stream().map(Student::getId).toList();
      case MENTOR -> access.assignedStudentIds(access.facultyOf(p));
      default -> throw ApiException.forbidden("Access denied");
    };
  }

  private Level level(UserPrincipal p, Map<String, Object> r) {
    Long studentId = (Long) r.get("student_id");
    boolean confidential = (Boolean) r.get("confidential");
    Long owner = (Long) r.get("owner_user_id");
    if (p.role() == Role.ADMIN) return Level.FULL;
    if (p.role() == Role.STUDENT) return students.findByUserId(p.id()).map(s -> s.getId().equals(studentId) ? Level.FULL : Level.NONE).orElse(Level.NONE);
    Faculty f = access.facultyOf(p);
    boolean inScope = access.assignedStudentIds(f).contains(studentId);
    boolean isOwner = owner != null && owner.equals(p.id());
    if (confidential) return f.isCounsellor() || isOwner ? Level.FULL : inScope ? Level.MASKED : Level.NONE;
    return inScope || isOwner ? Level.FULL : Level.NONE;
  }

  private Map<String, Object> load(Long id) {
    List<Map<String, Object>> l = jdbc.queryForList(BASE + " where r.id = ?", id);
    if (l.isEmpty()) throw ApiException.notFound("Support request not found");
    return l.get(0);
  }

  private static final String BASE = """
      select r.*, u.full_name as student_name, s.usn, s.semester from support_requests r
      join students s on s.id = r.student_id join users u on u.id = s.user_id""";

  // ------------------------------------------------------------------ student side

  @Transactional
  public Map<String, Object> submitAsStudent(UserPrincipal p, String category, String concern, String help) {
    Student s = access.studentOf(p); // identity comes from the session only
    Map<String, Object> cat = category(category);
    concern = required(concern, "Please describe your concern", 2000);
    help = help == null ? null : help.trim();
    if (help != null && help.length() > 2000) throw ApiException.bad("Help required is too long");
    String ownerRole = (String) cat.get("default_owner");
    boolean conf = (Boolean) cat.get("confidential");
    Faculty mentor = access.mentorOf(s.getId());
    Long ownerUser = "FACULTY_MENTOR".equals(ownerRole) && mentor != null ? mentor.getUserId() : null;
    Long id = insert(s.getId(), category, "STUDENT", concern, help, ownerUser != null ? "ASSIGNED" : "SUBMITTED", ownerRole, ownerUser, conf, null, p.id());
    event(id, "CREATED", "STUDENT", "SUBMITTED", "Request submitted", p.id());
    if (ownerUser != null) event(id, "ASSIGNED", "STUDENT", "ASSIGNED", "Assigned to " + roleLabel(ownerRole), null);

    String name = users.findById(s.getUserId()).map(User::getFullName).orElse("Student");
    String prio = priority("priority.request", "NORMAL");
    if (conf) {
      // confidential referral: no category details or concern in any notification
      for (Long uid : counsellorUserIds()) notifications.notify(uid, NotificationType.SUPPORT_REQUEST, "Confidential referral received",
          "A confidential support referral needs review by an authorised counsellor.", "/support?open=" + id, "REQ:" + id + ":" + uid, prio, null);
    } else if (mentor != null) {
      notifications.notify(mentor.getUserId(), NotificationType.SUPPORT_REQUEST, "Student Support Request",
          "Student: " + name + "\nUSN: " + s.getUsn() + "\nCategory: " + cat.get("name") + "\n\n" + abbreviate(concern, 240),
          "/support?open=" + id, "REQ:" + id + ":" + mentor.getUserId(), prio, s.getId());
    }
    return studentView(load(id));
  }

  public List<Map<String, Object>> listForStudent(UserPrincipal p) {
    Student s = access.studentOf(p);
    return jdbc.queryForList(BASE + " where r.student_id = ? order by r.created_at desc, r.id desc", s.getId()).stream()
        .filter(r -> "STUDENT".equals(r.get("source")) || hasStudentEvents((Long) r.get("id"))).map(this::studentView).toList();
  }

  private boolean hasStudentEvents(Long id) {
    return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from support_request_events where request_id = ? and visibility = 'STUDENT')", Boolean.class, id));
  }

  /** What a student may see: only STUDENT-visible timeline entries, never internal notes. */
  private Map<String, Object> studentView(Map<String, Object> r) {
    Long id = (Long) r.get("id");
    Map<String, Object> m = baseDto(r);
    if (!"STUDENT".equals(r.get("source"))) { // mentor-written observations stay with staff; the student sees only shared feedback
      m.put("concern", "Your mentor created a support action for you. Shared feedback appears below.");
      m.put("helpNeeded", null);
    }
    m.put("timeline", events(id, true));
    m.put("ownerName", ownerName(r));
    m.put("followUpDate", "STUDENT".equals(r.get("source")) || hasStudentEvents(id) ? str(r.get("follow_up_date")) : null);
    m.put("outcome", DONE.contains(r.get("status")) ? r.get("outcome") : null);
    m.put("progress", progress(r));
    return m;
  }

  /** Submitted → Owner assigned → In progress → Follow-up scheduled → Resolved */
  private List<Map<String, Object>> progress(Map<String, Object> r) {
    String st = (String) r.get("status");
    int idx = switch (st) { case "SUBMITTED" -> 0; case "ASSIGNED" -> 1; case "IN_PROGRESS" -> 2; case "FOLLOW_UP_REQUIRED" -> 3; default -> 4; };
    String[] labels = {"Request Submitted", "Owner Assigned", "In Progress", "Follow-up Scheduled", "Resolved"};
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < labels.length; i++) out.add(orderedMap("label", labels[i], "done", i <= idx));
    return out;
  }

  // ------------------------------------------------------------------ staff side

  @Transactional
  public Map<String, Object> createAction(UserPrincipal p, Long studentId, String category, String ownerRole, Long ownerUserId, String observation, Long signalId) {
    if (p.role() == Role.STUDENT) throw ApiException.forbidden("Students cannot create support actions");
    Student s = access.requireStudentAccess(p, studentId);
    Map<String, Object> cat = category(category);
    observation = required(observation, "Observation / action required is mandatory", 2000);
    if (ownerRole == null || ownerRole.isBlank()) ownerRole = (String) cat.get("default_owner");
    validateOwner(ownerRole, ownerUserId);
    Faculty mentor = access.mentorOf(s.getId());
    if (ownerUserId == null && "FACULTY_MENTOR".equals(ownerRole) && mentor != null) ownerUserId = mentor.getUserId();
    if (signalId != null && jdbc.queryForObject("select count(*) from support_signals where id = ? and student_id = ?", Integer.class, signalId, s.getId()) == 0) throw ApiException.bad("Signal does not belong to this student");
    boolean conf = (Boolean) cat.get("confidential");
    Long id = insert(s.getId(), category, "MENTOR", observation, null, ownerUserId != null ? "ASSIGNED" : "SUBMITTED", ownerRole, ownerUserId, conf, signalId, p.id());
    event(id, "CREATED", "INTERNAL", "SUBMITTED", "Support action created" + (signalId != null ? " from a support signal" : ""), p.id());
    if (ownerUserId != null) event(id, "ASSIGNED", "INTERNAL", "ASSIGNED", "Assigned to " + roleLabel(ownerRole) + (ownerUserId != null ? " (" + userName(ownerUserId) + ")" : ""), p.id());
    return staffView(p, load(id));
  }

  public List<Map<String, Object>> listForStaff(UserPrincipal p, Long studentId) {
    List<Map<String, Object>> all = studentId == null
        ? jdbc.queryForList(BASE + " order by r.created_at desc, r.id desc")
        : jdbc.queryForList(BASE + " where r.student_id = ? order by r.created_at desc, r.id desc", studentId);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r : all) { if (level(p, r) != Level.NONE) out.add(staffView(p, r)); }
    return out;
  }

  public Map<String, Object> getForStaff(UserPrincipal p, Long id) {
    Map<String, Object> r = load(id);
    if (level(p, r) == Level.NONE) throw ApiException.forbidden("Access denied");
    return staffView(p, r);
  }

  private Map<String, Object> staffView(UserPrincipal p, Map<String, Object> r) {
    Level lv = level(p, r);
    Map<String, Object> m = baseDto(r);
    m.put("ownerName", ownerName(r));
    m.put("followUpDate", str(r.get("follow_up_date")));
    m.put("followUpState", followUpState(r));
    m.put("signalId", r.get("signal_id"));
    if (lv == Level.MASKED) {
      m.put("restricted", true); m.put("concern", "Confidential request – details restricted to authorised counsellors"); m.put("helpNeeded", null);
      m.put("timeline", List.of()); m.put("outcome", null);
    } else {
      m.put("restricted", false);
      m.put("timeline", events((Long) r.get("id"), false));
      m.put("outcome", r.get("outcome"));
    }
    return m;
  }

  @Transactional
  public Map<String, Object> update(UserPrincipal p, Long id, Map<String, Object> in) {
    Map<String, Object> r = load(id);
    if (p.role() == Role.STUDENT || level(p, r) != Level.FULL) throw ApiException.forbidden("Only the action owner or an authorised user can update this request");
    String cur = (String) r.get("status");
    if ("CLOSED".equals(cur)) throw ApiException.bad("A closed request cannot be changed");
    boolean studentSource = "STUDENT".equals(r.get("source"));
    String evVis = studentSource ? "STUDENT" : "INTERNAL";
    String status = cur;

    String newOwnerRole = text(in, "ownerRole");
    Long newOwnerUser = in.get("ownerUserId") == null || in.get("ownerUserId").toString().isBlank() ? null : Long.valueOf(in.get("ownerUserId").toString());
    if (newOwnerRole != null || newOwnerUser != null) {
      String role = newOwnerRole != null ? newOwnerRole : (String) r.get("owner_role");
      validateOwner(role, newOwnerUser);
      jdbc.update("update support_requests set owner_role = ?, owner_user_id = ? where id = ?", role, newOwnerUser != null ? newOwnerUser : r.get("owner_user_id"), id);
      event(id, "ASSIGNED", evVis, "ASSIGNED", "Assigned to " + roleLabel(role) + (newOwnerUser != null ? " (" + userName(newOwnerUser) + ")" : ""), p.id());
      if ("SUBMITTED".equals(cur)) status = "ASSIGNED";
    }
    String reqStatus = text(in, "status");
    if (reqStatus != null) {
      if (!STATUSES.contains(reqStatus)) throw ApiException.bad("Unknown status " + reqStatus);
      status = reqStatus;
    }
    String followUp = text(in, "followUpDate");
    if (followUp != null) {
      LocalDate d;
      try { d = LocalDate.parse(followUp); } catch (Exception e) { throw ApiException.bad("Invalid follow-up date"); }
      jdbc.update("update support_requests set follow_up_date = ?, reminded_for = null where id = ?", Date.valueOf(d), id);
      event(id, "FOLLOW_UP", evVis, null, "Follow-up scheduled for " + d, p.id());
      if (reqStatus == null && !DONE.contains(status)) status = "FOLLOW_UP_REQUIRED";
    }
    String note = text(in, "internalNote");
    if (note != null) event(id, "NOTE", "INTERNAL", null, required(note, "Note is empty", 2000), p.id());
    String feedback = text(in, "studentFeedback");
    if (feedback != null) {
      event(id, "FEEDBACK", "STUDENT", null, required(feedback, "Feedback is empty", 2000), p.id());
      students.findById((Long) r.get("student_id")).ifPresent(s -> notifications.notify(s.getUserId(), NotificationType.SUPPORT_REQUEST, "New feedback on your support request",
          abbreviate(feedback, 200), "/student/support", null, "NORMAL", null));
    }
    String outcome = text(in, "outcome");
    if (outcome != null) jdbc.update("update support_requests set outcome = ? where id = ?", required(outcome, "Outcome is empty", 2000), id);
    if (DONE.contains(status)) {
      boolean hasOutcome = outcome != null || (r.get("outcome") != null && !r.get("outcome").toString().isBlank());
      if ("RESOLVED".equals(status) && !hasOutcome) throw ApiException.bad("Record the outcome before resolving a support request");
      if (outcome != null) event(id, "OUTCOME", "STUDENT", status, outcome.trim(), p.id());
    }
    final String newStatus = status;
    if (!status.equals(cur)) {
      jdbc.update("update support_requests set status = ?, resolved_at = ? where id = ?", status, DONE.contains(status) ? new Timestamp(System.currentTimeMillis()) : null, id);
      event(id, "STATUS", evVis, status, "Status changed to " + status.replace('_', ' ').toLowerCase(), p.id());
      if (studentSource || feedback != null) {
        students.findById((Long) r.get("student_id")).ifPresent(s -> notifications.notify(s.getUserId(), NotificationType.SUPPORT_REQUEST, "Support request update",
            "Your support request is now: " + newStatus.replace('_', ' ').toLowerCase() + ".", "/student/support", null, "NORMAL", null));
      }
    }
    jdbc.update("update support_requests set updated_at = now() where id = ?", id);
    return staffView(p, load(id));
  }

  /** Owner dropdown source: faculty users (mentors) that can own an action. */
  public List<Map<String, Object>> ownerCandidates() {
    return jdbc.queryForList("select u.id, u.full_name as name, f.is_counsellor as counsellor from faculty f join users u on u.id = f.user_id where u.enabled order by u.full_name");
  }

  public List<Map<String, Object>> categories(boolean includeInactive) {
    return jdbc.queryForList("select code, name, default_owner as \"defaultOwner\", confidential, active from support_categories " + (includeInactive ? "" : "where active ") + "order by sort_order");
  }

  // ------------------------------------------------------------------ follow-up reminders

  /** Creates one reminder per (request, follow-up date) once the date arrives. Safe to run repeatedly. */
  @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
  @Transactional
  public int sendDueReminders() { return sendDueReminders(LocalDate.now()); }

  @Transactional
  public int sendDueReminders(LocalDate today) {
    int n = 0;
    for (var r : jdbc.queryForList(BASE + " where r.follow_up_date is not null and r.follow_up_date <= ? and r.status not in ('RESOLVED','CLOSED')", Date.valueOf(today))) {
      Long id = (Long) r.get("id");
      LocalDate due = ((Date) r.get("follow_up_date")).toLocalDate();
      Long recipient = (Long) r.get("owner_user_id");
      if (recipient == null) { Faculty m = access.mentorOf((Long) r.get("student_id")); if (m != null) recipient = m.getUserId(); }
      if (recipient == null) continue;
      boolean conf = (Boolean) r.get("confidential");
      String desc = conf ? "A confidential support follow-up is due." : "Follow-up due for " + r.get("student_name") + " (" + r.get("usn") + ") – " + categoryName((String) r.get("category")) + ".";
      if (notifications.notify(recipient, NotificationType.FOLLOW_UP_REMINDER, "Follow-up Reminder", desc, "/support?open=" + id, "FU:" + id + ":" + due,
          priority("priority.followup", "NORMAL"), conf ? null : (Long) r.get("student_id"))) n++;
    }
    return n;
  }

  // ------------------------------------------------------------------ helpers

  private Long insert(Long studentId, String category, String source, String concern, String help, String status, String ownerRole, Long ownerUser, boolean conf, Long signalId, Long createdBy) {
    return jdbc.queryForObject("""
        insert into support_requests(student_id, category, source, concern, help_needed, status, owner_role, owner_user_id, confidential, signal_id, created_by)
        values (?,?,?,?,?,?,?,?,?,?,?) returning id""", Long.class, studentId, category, source, concern, help, status, ownerRole, ownerUser, conf, signalId, createdBy);
  }

  private void event(Long requestId, String type, String visibility, String status, String message, Long author) {
    jdbc.update("insert into support_request_events(request_id, event_type, visibility, status, message, author_user_id) values (?,?,?,?,?,?)", requestId, type, visibility, status, message, author);
  }

  private List<Map<String, Object>> events(Long requestId, boolean studentOnly) {
    return jdbc.queryForList("select e.id, e.event_type as \"type\", e.visibility, e.status, e.message, e.created_at as \"createdAt\", u.full_name as author from support_request_events e "
        + "left join users u on u.id = e.author_user_id where e.request_id = ? " + (studentOnly ? "and e.visibility = 'STUDENT' " : "") + "order by e.created_at, e.id", requestId)
        .stream().map(m -> { m.put("createdAt", ((Timestamp) m.get("createdAt")).toInstant()); return m; }).toList();
  }

  private Map<String, Object> baseDto(Map<String, Object> r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.get("id")); m.put("studentId", r.get("student_id")); m.put("studentName", r.get("student_name")); m.put("usn", r.get("usn"));
    m.put("category", r.get("category")); m.put("categoryName", categoryName((String) r.get("category")));
    m.put("source", r.get("source")); m.put("concern", r.get("concern")); m.put("helpNeeded", r.get("help_needed"));
    m.put("status", r.get("status")); m.put("ownerRole", r.get("owner_role")); m.put("ownerRoleLabel", roleLabel((String) r.get("owner_role")));
    m.put("ownerUserId", r.get("owner_user_id")); m.put("confidential", r.get("confidential"));
    m.put("createdAt", ((Timestamp) r.get("created_at")).toInstant()); m.put("updatedAt", ((Timestamp) r.get("updated_at")).toInstant());
    m.put("resolvedAt", r.get("resolved_at") == null ? null : ((Timestamp) r.get("resolved_at")).toInstant());
    return m;
  }

  private String followUpState(Map<String, Object> r) {
    if (r.get("follow_up_date") == null || DONE.contains(r.get("status"))) return null;
    LocalDate d = ((Date) r.get("follow_up_date")).toLocalDate();
    return d.isBefore(LocalDate.now()) ? "OVERDUE" : d.isEqual(LocalDate.now()) ? "DUE" : "UPCOMING";
  }

  private Map<String, Object> category(String code) {
    List<Map<String, Object>> l = jdbc.queryForList("select * from support_categories where code = ? and active", code == null ? "" : code);
    if (l.isEmpty()) throw ApiException.bad("Unknown or inactive support category");
    return l.get(0);
  }
  private String categoryName(String code) { return jdbc.queryForList("select name from support_categories where code = ?", String.class, code).stream().findFirst().orElse(code); }

  private void validateOwner(String role, Long userId) {
    if (!OWNER_ROLES.contains(role)) throw ApiException.bad("Unknown action owner " + role);
    if (userId != null && jdbc.queryForObject("select count(*) from faculty f join users u on u.id = f.user_id where u.id = ? and u.enabled", Integer.class, userId) == 0
        && jdbc.queryForObject("select count(*) from users where id = ? and role = 'ADMIN'", Integer.class, userId) == 0) throw ApiException.bad("Selected owner is not a staff member");
  }

  private List<Long> counsellorUserIds() {
    List<Long> l = jdbc.queryForList("select user_id from faculty where is_counsellor", Long.class);
    return l.isEmpty() ? jdbc.queryForList("select id from users where role = 'ADMIN' and enabled", Long.class) : l;
  }

  private String ownerName(Map<String, Object> r) { return r.get("owner_user_id") == null ? null : userName((Long) r.get("owner_user_id")); }
  private String userName(Long id) { return users.findById(id).map(User::getFullName).orElse("Staff"); }
  private String priority(String key, String def) { String v = settings.get(key, def).toUpperCase(); return List.of("NORMAL", "MEDIUM", "HIGH").contains(v) ? v : def; }
  public static String roleLabel(String role) {
    return Arrays.stream(role.split("_")).map(w -> w.charAt(0) + w.substring(1).toLowerCase()).collect(Collectors.joining(" "));
  }
  private static String str(Object o) { return o == null ? null : o.toString(); }
  private static String text(Map<String, Object> m, String k) { Object v = m.get(k); return v == null || v.toString().isBlank() ? null : v.toString().trim(); }
  private static String required(String v, String msg, int max) {
    if (v == null || v.isBlank()) throw ApiException.bad(msg);
    if (v.trim().length() > max) throw ApiException.bad("Text is too long (max " + max + " characters)");
    return v.trim();
  }
  private static String abbreviate(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
  private static Map<String, Object> orderedMap(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }
}
