package com.mentora.service;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Department Overview and the student-facing support summary. Everything is computed from database records
 * (never from static values); signals are refreshed before each read so the page reflects the latest records.
 *
 * Average attendance = arithmetic mean of each student's own attendance percentage, counting only students that have
 * at least one conducted class (students without records are excluded rather than counted as 0%).
 */
@Service
public class OverviewService {
  private final SupportService support;
  private final SupportSignalService signals;
  private final StudentRepository students;
  private final UserRepository users;
  private final AttendanceRepository attendance;
  private final AttendanceCalc calc;
  private final DepartmentRepository departments;
  private final JdbcTemplate jdbc;
  private final AccessService access;
  private final MarkRepository marks;

  public OverviewService(SupportService support, SupportSignalService signals, StudentRepository students, UserRepository users, AttendanceRepository attendance, AttendanceCalc calc,
                         DepartmentRepository departments, JdbcTemplate jdbc, AccessService access, MarkRepository marks) {
    this.support = support; this.signals = signals; this.students = students; this.users = users; this.attendance = attendance; this.calc = calc;
    this.departments = departments; this.jdbc = jdbc; this.access = access; this.marks = marks;
  }

  private record Built(List<Map<String, Object>> rows, Map<Long, List<SupportSignalService.SignalRow>> active) {}

  private Built build(UserPrincipal p) {
    List<Long> ids = support.scope(p);
    List<Student> sts = students.findByIdIn(ids);
    Map<Long, String> names = users.findAllById(sts.stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, User::getFullName));
    Map<Long, String> depts = departments.findAll().stream().collect(Collectors.toMap(Department::getId, Department::getCode));
    Map<Long, List<Attendance>> att = attendance.findByStudentIdIn(ids).stream().collect(Collectors.groupingBy(Attendance::getStudentId));
    Map<Long, List<SupportSignalService.SignalRow>> active = signals.activeSignals(ids).stream().collect(Collectors.groupingBy(SupportSignalService.SignalRow::studentId));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Student s : sts) {
      var sum = calc.summarize(att.getOrDefault(s.getId(), List.of()));
      List<SupportSignalService.SignalRow> act = active.getOrDefault(s.getId(), List.of());
      List<SupportSignalService.SignalRow> base = act.stream().filter(x -> SupportSignalService.BASE_TYPES.contains(x.type())).toList();
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("studentId", s.getId()); r.put("name", names.get(s.getUserId())); r.put("usn", s.getUsn()); r.put("semester", s.getSemester()); r.put("department", depts.get(s.getDepartmentId()));
      r.put("attendancePct", sum.conducted() == 0 ? null : sum.percent());
      r.put("hasAttendance", sum.conducted() > 0);
      r.put("overdueAssignments", base.stream().filter(x -> x.type().equals(SupportSignalService.ASSIGNMENT)).map(x -> ((Number) x.details().get("overdueCount")).intValue()).findFirst().orElse(0));
      r.put("iaAttended", base.stream().filter(x -> x.type().equals(SupportSignalService.IA)).map(x -> ((Number) x.details().get("attended")).intValue()).findFirst().orElse(null));
      r.put("consecutiveAbsences", base.stream().filter(x -> x.type().equals(SupportSignalService.CONSECUTIVE)).map(x -> ((Number) x.details().get("streak")).intValue()).findFirst().orElse(0));
      r.put("signalTypes", base.stream().map(SupportSignalService.SignalRow::type).toList());
      r.put("reasons", base.stream().map(SupportSignalService.SignalRow::summary).toList());
      r.put("titles", base.stream().map(SupportSignalService.SignalRow::title).toList());
      String prio = base.stream().map(SupportSignalService.SignalRow::priority).max(Comparator.comparingInt(OverviewService::rank)).orElse("NONE");
      if (base.size() >= 2) prio = "HIGH".equals(prio) ? "HIGH" : act.stream().anyMatch(x -> x.type().equals(SupportSignalService.MULTIPLE)) ? act.stream().filter(x -> x.type().equals(SupportSignalService.MULTIPLE)).findFirst().get().priority() : prio;
      r.put("priority", prio);
      r.put("category", base.isEmpty() ? "NONE" : base.size() >= 2 ? "MULTIPLE" : "REVIEW");
      r.put("label", base.isEmpty() ? "No Current Support Signal" : base.size() >= 2 ? "Multiple Support Signals" : "HIGH".equals(prio) ? "Mentor Review Required" : "Support Review Recommended");
      rows.add(r);
    }
    rows.sort(Comparator.comparing((Map<String, Object> r) -> -rank((String) r.get("priority"))).thenComparing(r -> -((List<?>) r.get("signalTypes")).size()).thenComparing(r -> (String) r.get("usn")));
    return new Built(rows, active);
  }

  private static int rank(String p) { return switch (p) { case "HIGH" -> 3; case "MEDIUM" -> 2; case "NORMAL" -> 1; default -> 0; }; }

  @SuppressWarnings("unchecked")
  public Map<String, Object> overview(UserPrincipal p) {
    Built b = build(p);
    List<Map<String, Object>> rows = b.rows();
    List<Double> pcts = rows.stream().filter(r -> (Boolean) r.get("hasAttendance")).map(r -> ((Number) r.get("attendancePct")).doubleValue()).toList();
    int review = 0, multiple = 0;
    for (var r : rows) { if ("REVIEW".equals(r.get("category"))) review++; else if ("MULTIPLE".equals(r.get("category"))) multiple++; }
    List<Long> ids = rows.stream().map(r -> (Long) r.get("studentId")).toList();
    int open = ids.isEmpty() ? 0 : jdbc.queryForObject("select count(*) from support_requests where status not in ('RESOLVED','CLOSED') and student_id in (" + csv(ids) + ")", Integer.class);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("scope", p.role() == Role.ADMIN ? "INSTITUTION" : "ASSIGNED_STUDENTS");
    out.put("studentsInView", rows.size());
    out.put("averageAttendance", pcts.isEmpty() ? null : Math.round(pcts.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0);
    out.put("studentsRequiringSupport", review + multiple);
    out.put("openInterventions", open);
    out.put("distribution", Map.of("none", rows.size() - review - multiple, "review", review, "multiple", multiple));
    out.put("metrics", Map.of(
        "belowAttendance", count(rows, SupportSignalService.ATTENDANCE), "overdueAssignments", count(rows, SupportSignalService.ASSIGNMENT),
        "iaLow", count(rows, SupportSignalService.IA), "consecutiveAbsence", count(rows, SupportSignalService.CONSECUTIVE)));
    out.put("attention", rows.stream().filter(r -> !"NONE".equals(r.get("category"))).limit(8).toList());
    out.put("attentionTotal", review + multiple);
    return out;
  }

  /** category: ANY (default), NONE, REVIEW, MULTIPLE or a signal type; priority: HIGH/MEDIUM/NORMAL; q: name or USN. */
  public List<Map<String, Object>> students(UserPrincipal p, String category, String priority, String q) {
    String cat = category == null || category.isBlank() ? "ANY" : category;
    String needle = q == null ? "" : q.trim().toLowerCase();
    return build(p).rows().stream().filter(r -> switch (cat) {
      case "ANY" -> !"NONE".equals(r.get("category"));
      case "ALL" -> true;
      case "NONE", "REVIEW", "MULTIPLE" -> cat.equals(r.get("category"));
      default -> ((List<?>) r.get("signalTypes")).contains(cat);
    }).filter(r -> priority == null || priority.isBlank() || priority.equals(r.get("priority")))
        .filter(r -> needle.isEmpty() || ((String) r.get("name")).toLowerCase().contains(needle) || ((String) r.get("usn")).toLowerCase().contains(needle)).toList();
  }

  private static long count(List<Map<String, Object>> rows, String type) { return rows.stream().filter(r -> ((List<?>) r.get("signalTypes")).contains(type)).count(); }
  private static String csv(Collection<Long> ids) { return ids.stream().map(String::valueOf).collect(Collectors.joining(",")); }

  // ------------------------------------------------------------------ one student (mentor profile / student dashboard)

  /** Support section for a student. {@code forStudent} strips mentor-side wording and anything internal. */
  public Map<String, Object> studentSupport(Long studentId, boolean forStudent) {
    Student s = students.findById(studentId).orElseThrow();
    signals.evaluate(List.of(studentId), LocalDate.now());
    var all = signals.signalsFor(studentId, false);
    var active = all.stream().filter(x -> x.status().equals("ACTIVE")).toList();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("activeSignals", active.stream().map(x -> signalDto(x, forStudent)).toList());
    if (!forStudent) out.put("signalHistory", all.stream().filter(x -> x.status().equals("RESOLVED")).limit(20).map(x -> signalDto(x, false)).toList());

    // IA attendance (separate from IA marks)
    Map<Integer, String> ia = new TreeMap<>(Map.of(1, "NOT_CONDUCTED", 2, "NOT_CONDUCTED", 3, "NOT_CONDUCTED"));
    jdbc.query("select e.ia_number, e.conducted, a.status from ia_exams e left join ia_exam_attendance a on a.exam_id = e.id and a.student_id = ? where e.semester = ?", rs -> {
      if (rs.getBoolean("conducted")) ia.put(rs.getInt("ia_number"), rs.getString("status") == null ? "NOT_RECORDED" : rs.getString("status"));
    }, studentId, s.getSemester());
    Map<Integer, Double> iaMarks = new TreeMap<>();
    for (var m : marks.findAll()) if (m.getStudentId().equals(studentId) && m.getComponent().isIa() && m.getScore() != null) iaMarks.merge(Integer.parseInt(m.getComponent().name().substring(2)), m.getScore().doubleValue(), Double::sum);
    List<Map<String, Object>> iaRows = new ArrayList<>();
    for (int i = 1; i <= 3; i++) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ia", i); m.put("attendance", ia.get(i)); m.put("marks", iaMarks.get(i)); iaRows.add(m); }
    out.put("ia", iaRows);

    // assignments
    LocalDate today = LocalDate.now();
    var asg = jdbc.queryForList("""
        select a.id, a.title, a.deadline::text as deadline, sub.name as subject,
               exists(select 1 from assignment_submissions x where x.assignment_id = a.id and x.student_id = ?) as submitted
        from assignments a join subjects sub on sub.id = a.subject_id where sub.semester = ? order by a.deadline""", studentId, s.getSemester());
    for (var a : asg) {
      boolean sub = (Boolean) a.get("submitted");
      a.put("state", sub ? "SUBMITTED" : LocalDate.parse((String) a.get("deadline")).isBefore(today) ? "OVERDUE" : "PENDING");
    }
    out.put("assignments", asg);
    out.put("overdueAssignments", asg.stream().filter(a -> "OVERDUE".equals(a.get("state"))).toList());
    out.put("pendingAssignments", asg.stream().filter(a -> "PENDING".equals(a.get("state"))).toList());
    return out;
  }

  private Map<String, Object> signalDto(SupportSignalService.SignalRow r, boolean forStudent) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id()); m.put("type", r.type()); m.put("status", r.status());
    m.put("title", r.title()); m.put("summary", r.summary()); m.put("details", r.details());
    m.put("firstDetectedAt", r.firstDetectedAt()); m.put("lastUpdatedAt", r.lastUpdatedAt()); m.put("resolvedAt", r.resolvedAt());
    if (!forStudent) m.put("priority", r.priority());
    return m;
  }
}
