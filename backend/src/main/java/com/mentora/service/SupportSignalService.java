package com.mentora.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentora.domain.*;
import com.mentora.repo.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Automatic early-warning engine. Reads real attendance, assignment and IA-attendance records, keeps one ACTIVE
 * support signal per (student, type), resolves it when the condition clears (history is retained) and opens a brand
 * new signal if the condition returns. Mentors are notified once per signal occurrence.
 * Signals describe a possible need for support; they never penalise or label a student.
 */
@Service
public class SupportSignalService {
  public static final String ATTENDANCE = "ATTENDANCE", ASSIGNMENT = "ASSIGNMENT", IA = "IA_ATTENDANCE", CONSECUTIVE = "CONSECUTIVE_ABSENCE", MULTIPLE = "MULTIPLE";
  public static final List<String> BASE_TYPES = List.of(ATTENDANCE, ASSIGNMENT, IA, CONSECUTIVE);
  private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

  public record SignalRow(Long id, Long studentId, String type, String status, String priority, String title, String summary,
                          Map<String, Object> details, Instant firstDetectedAt, Instant lastUpdatedAt, Instant resolvedAt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StudentRepository students;
  private final UserRepository users;
  private final AttendanceRepository attendance;
  private final SubjectRepository subjects;
  private final AttendanceCalc calc;
  private final SettingsService settings;
  private final AccessService access;
  private final NotificationService notifications;

  public SupportSignalService(JdbcTemplate jdbc, ObjectMapper json, StudentRepository students, UserRepository users, AttendanceRepository attendance,
                              SubjectRepository subjects, AttendanceCalc calc, SettingsService settings, AccessService access, NotificationService notifications) {
    this.jdbc = jdbc; this.json = json; this.students = students; this.users = users; this.attendance = attendance; this.subjects = subjects;
    this.calc = calc; this.settings = settings; this.access = access; this.notifications = notifications;
  }

  // ------------------------------------------------------------------ evaluation entry points

  /** Evaluates every rule for the given students as of {@code today}. Returns the number of new notifications. */
  @Transactional
  public int evaluate(Collection<Long> studentIds, LocalDate today) {
    int created = 0;
    for (Student s : students.findByIdIn(studentIds)) created += evaluateStudent(s, today);
    return created;
  }

  @Transactional
  public int evaluateNow(Collection<Long> studentIds) { return evaluate(studentIds, LocalDate.now()); }

  @Transactional
  public int evaluateAll(LocalDate today) {
    return evaluate(students.findAll().stream().map(Student::getId).toList(), today);
  }

  private int evaluateStudent(Student s, LocalDate today) {
    String name = users.findById(s.getUserId()).map(User::getFullName).orElse("Student");
    Faculty mentor = access.mentorOf(s.getId());
    int n = 0;
    n += attendanceRule(s, name, mentor, today);
    n += consecutiveRule(s, name, mentor, today);
    n += assignmentRule(s, name, mentor, today);
    n += iaRule(s, name, mentor);
    n += multipleRule(s, name, mentor);
    return n;
  }

  // ------------------------------------------------------------------ rule 1: overall attendance below threshold

  private int attendanceRule(Student s, String name, Faculty mentor, LocalDate today) {
    double threshold = settings.num("attendance.threshold", 75);
    List<Attendance> all = attendance.findByStudentIdOrderByDate(s.getId()).stream().filter(a -> !a.getDate().isAfter(today)).toList();
    var sum = calc.summarize(all);
    boolean breach = sum.conducted() > 0 && sum.percent() < threshold; // exactly at the threshold is not a breach
    if (!breach) { resolve(s.getId(), ATTENDANCE); return 0; }

    Map<Long, String> subjectNames = subjects.findAll().stream().collect(Collectors.toMap(Subject::getId, Subject::getName));
    List<Map<String, Object>> perSubject = new ArrayList<>();
    all.stream().collect(Collectors.groupingBy(Attendance::getSubjectId, TreeMap::new, Collectors.toList())).forEach((sid, recs) -> {
      var ss = calc.summarize(recs);
      if (ss.conducted() > 0) perSubject.add(map("subject", subjectNames.getOrDefault(sid, "Subject"), "attended", ss.attended(), "total", ss.conducted(), "percent", ss.percent()));
    });
    int missed = sum.conducted() - sum.attended();
    Map<String, Object> d = map("percent", sum.percent(), "attended", sum.attended(), "total", sum.conducted(), "missed", missed, "threshold", threshold, "subjects", perSubject);
    String summary = "Attendance: " + fmt(sum.percent()) + "% · Classes attended: " + sum.attended() + " · Total classes: " + sum.conducted() + " · Classes missed: " + missed
        + ". Reason: attendance has fallen below the institutional threshold of " + fmt(threshold) + "%.";
    String description = "Student: " + name + "\nUSN: " + s.getUsn() + "\nAttendance: " + fmt(sum.percent()) + "%\nClasses attended: " + sum.attended()
        + "\nTotal classes: " + sum.conducted() + "\nClasses missed: " + missed
        + "\n\nReason: Attendance has fallen below the institutional threshold of " + fmt(threshold) + "%."
        + "\n\nSuggested action: Contact the student and discuss an attendance improvement plan.";
    return upsert(s, mentor, ATTENDANCE, priority("priority.attendance", "MEDIUM"), "Attendance Support Required", summary, description, d, NotificationType.LOW_ATTENDANCE, null);
  }

  // ------------------------------------------------------------------ rule 2: assignments not submitted after deadline

  private int assignmentRule(Student s, String name, Faculty mentor, LocalDate today) {
    int grace = settings.integer("signals.assignment_grace_days", 0);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select a.id, a.title, a.deadline, sub.name as subject,
               exists(select 1 from assignment_submissions x where x.assignment_id = a.id and x.student_id = ?) as submitted
        from assignments a join subjects sub on sub.id = a.subject_id
        where sub.semester = ? order by a.deadline, a.id""", s.getId(), s.getSemester());
    List<Map<String, Object>> overdue = new ArrayList<>(), pending = new ArrayList<>();
    for (var r : rows) {
      if ((Boolean) r.get("submitted")) continue;
      LocalDate dl = ((java.sql.Date) r.get("deadline")).toLocalDate();
      Map<String, Object> item = map("id", r.get("id"), "title", r.get("title"), "subject", r.get("subject"), "deadline", dl.toString());
      if (today.isAfter(dl.plusDays(grace))) overdue.add(item); else pending.add(item);
    }
    if (overdue.isEmpty()) { resolve(s.getId(), ASSIGNMENT); return 0; }
    String summary = overdue.size() + " assignment" + (overdue.size() == 1 ? "" : "s") + " not submitted after the deadline: "
        + overdue.stream().map(o -> o.get("subject") + " – " + o.get("title")).collect(Collectors.joining("; ")) + ".";
    Map<String, Object> d = map("overdueCount", overdue.size(), "overdue", overdue, "pendingCount", pending.size(), "pending", pending);
    int created = upsert(s, mentor, ASSIGNMENT, priority("priority.assignment", "MEDIUM"), "Assignment Submission Alert", summary, null, d, NotificationType.ASSIGNMENT_OVERDUE, null);
    // one notification per newly overdue assignment (de-duplicated per student + assignment)
    if (mentor != null) {
      for (var o : overdue) {
        String desc = "Student: " + name + "\nUSN: " + s.getUsn() + "\nSubject: " + o.get("subject") + "\nAssignment: " + o.get("title")
            + "\nDeadline: " + LocalDate.parse((String) o.get("deadline")).format(LONG_DATE) + "\nStatus: Not Submitted"
            + "\n\nSuggested action: Mentor review and assignment support recommended.";
        if (notifications.notify(mentor.getUserId(), NotificationType.ASSIGNMENT_OVERDUE, "Assignment Submission Alert", desc, link(s), "ASG:" + s.getId() + ":" + o.get("id"),
            priority("priority.assignment", "MEDIUM"), s.getId())) created++;
      }
    }
    return created;
  }

  // ------------------------------------------------------------------ rule 3: IA examination attendance

  private int iaRule(Student s, String name, Faculty mentor) {
    int min = settings.integer("signals.ia_min_attended", 2);
    Map<Integer, String> status = new TreeMap<>();
    for (int i = 1; i <= 3; i++) status.put(i, "NOT_CONDUCTED");
    jdbc.query("""
        select e.ia_number, e.conducted, a.status from ia_exams e
        left join ia_exam_attendance a on a.exam_id = e.id and a.student_id = ? where e.semester = ?""", rs -> {
      if (rs.getBoolean("conducted") && rs.getString("status") != null) status.put(rs.getInt("ia_number"), rs.getString("status"));
    }, s.getId(), s.getSemester());
    int attended = (int) status.values().stream().filter("ATTENDED"::equals).count();
    int absent = (int) status.values().stream().filter("ABSENT"::equals).count();
    int pending = 3 - attended - absent; // not yet conducted, or conducted but not yet recorded: never counted as absent
    if (attended + pending >= min) { resolve(s.getId(), IA); return 0; }

    boolean none = attended == 0;
    String lines = status.entrySet().stream().map(e -> "IA " + e.getKey() + ": " + pretty(e.getValue())).collect(Collectors.joining("\n"));
    String summary = "Attended " + attended + " of 3 internal assessment examinations (" + status.entrySet().stream().map(e -> "IA " + e.getKey() + " " + pretty(e.getValue())).collect(Collectors.joining(", ")) + ").";
    String description = "Student: " + name + "\nUSN: " + s.getUsn() + "\n\n" + lines + "\n\nThe student has attended only " + attended + " out of 3 internal assessment examinations."
        + "\n\nSuggested action: Mentor interaction and academic support review recommended.";
    Map<String, Object> d = map("attended", attended, "absent", absent, "pending", pending, "minimum", min,
        "exams", status.entrySet().stream().map(e -> map("ia", e.getKey(), "status", e.getValue())).toList());
    return upsert(s, mentor, IA, priority(none ? "priority.ia_none" : "priority.ia_partial", none ? "HIGH" : "MEDIUM"),
        none ? "Internal Assessment Attendance Alert – Mentor Review Required" : "Internal Assessment Attendance Alert", summary, description, d, NotificationType.IA_ATTENDANCE, null);
  }

  // ------------------------------------------------------------------ rule 4: consecutive scheduled absent days

  private int consecutiveRule(Student s, String name, Faculty mentor, LocalDate today) {
    int limit = settings.integer("signals.consecutive_absence_days", 3);
    Set<LocalDate> holidays = jdbc.queryForList("select holiday_date from holidays", java.sql.Date.class).stream().map(java.sql.Date::toLocalDate).collect(Collectors.toSet());
    var daily = calc.daily(attendance.findByStudentIdOrderByDate(s.getId())).stream()
        .filter(x -> !x.date().isAfter(today) && x.status() != AttStatus.NC && !holidays.contains(x.date())
            && x.date().getDayOfWeek() != DayOfWeek.SATURDAY && x.date().getDayOfWeek() != DayOfWeek.SUNDAY).toList();
    int streak = 0;
    for (int i = daily.size() - 1; i >= 0 && daily.get(i).status() == AttStatus.A; i--) streak++;
    if (streak < limit) { resolve(s.getId(), CONSECUTIVE); return 0; }

    LocalDate lastPresent = daily.size() > streak ? daily.get(daily.size() - 1 - streak).date() : null;
    LocalDate lastRecorded = daily.get(daily.size() - 1).date();
    String summary = "Absent for " + streak + " consecutive scheduled class days (last recorded attendance entry: " + lastRecorded.format(LONG_DATE) + ").";
    String description = "Student: " + name + "\nUSN: " + s.getUsn() + "\n\nThe student has been absent for " + streak + " consecutive scheduled class days."
        + "\n\nLast recorded attendance: " + lastRecorded.format(LONG_DATE) + "."
        + "\n\nSuggested action: Contact the student and understand whether academic or institutional support is required.";
    Map<String, Object> d = map("streak", streak, "threshold", limit, "lastRecorded", lastRecorded.toString(), "lastPresent", lastPresent == null ? "" : lastPresent.toString());
    return upsert(s, mentor, CONSECUTIVE, priority("priority.consecutive", "MEDIUM"), "Consecutive Absence Alert", summary, description, d, NotificationType.CONSECUTIVE_ABSENCE, null);
  }

  // ------------------------------------------------------------------ consolidated summary

  private int multipleRule(Student s, String name, Faculty mentor) {
    List<SignalRow> active = signalsFor(s.getId(), true).stream().filter(x -> BASE_TYPES.contains(x.type())).toList();
    if (active.size() < 2) { resolve(s.getId(), MULTIPLE); return 0; }
    StringBuilder obs = new StringBuilder();
    int i = 1;
    for (SignalRow r : active) obs.append(i++).append(". ").append(observation(r)).append('\n');
    String summary = active.size() + " academic support signals are active: " + active.stream().map(r -> observation(r)).collect(Collectors.joining(" | "));
    String description = "Student: " + name + "\nUSN: " + s.getUsn() + "\n\nObserved conditions:\n" + obs
        + "\nSuggested action: Mentor interaction and an academic support plan are recommended.";
    Map<String, Object> d = map("count", active.size(), "types", active.stream().map(SignalRow::type).toList(), "conditions", active.stream().map(this::observation).toList());
    return upsert(s, mentor, MULTIPLE, priority("priority.multiple", "HIGH"), "Multiple Academic Support Signals", summary, description, d, NotificationType.MULTIPLE_SIGNALS, null);
  }

  @SuppressWarnings("unchecked")
  private String observation(SignalRow r) {
    var d = r.details();
    return switch (r.type()) {
      case ATTENDANCE -> "Attendance below the institutional threshold (" + fmt(((Number) d.get("percent")).doubleValue()) + "%).";
      case ASSIGNMENT -> {
        int c = ((Number) d.get("overdueCount")).intValue();
        yield (c == 1 ? "One overdue assignment" : numberWord(c) + " overdue assignments") + ".";
      }
      case IA -> "Attendance in only " + ((Number) d.get("attended")).intValue() + " of 3 IA examinations.";
      case CONSECUTIVE -> numberWord(((Number) d.get("streak")).intValue()) + " consecutive scheduled absent class days.";
      default -> r.title();
    };
  }

  // ------------------------------------------------------------------ persistence helpers

  /** Creates the signal (and notifies the mentor) or refreshes the active one. Returns the number of new notifications. */
  private int upsert(Student s, Faculty mentor, String type, String priority, String title, String summary, String description, Map<String, Object> details,
                     NotificationType nType, String unused) {
    String detailsJson = write(details);
    List<Long> existing = jdbc.queryForList("select id from support_signals where student_id = ? and signal_type = ? and status = 'ACTIVE'", Long.class, s.getId(), type);
    Long id;
    boolean isNew = existing.isEmpty();
    if (!isNew) {
      id = existing.get(0);
      jdbc.update("update support_signals set priority = ?, title = ?, summary = ?, details = ?, last_updated_at = now() where id = ? and (summary <> ? or priority <> ? or details <> ?)",
          priority, title, summary, detailsJson, id, summary, priority, detailsJson);
    } else {
      try {
        id = jdbc.queryForObject("insert into support_signals(student_id, signal_type, priority, title, summary, details) values (?,?,?,?,?,?) returning id", Long.class,
            s.getId(), type, priority, title, summary, detailsJson);
      } catch (DuplicateKeyException e) { return 0; } // another evaluation created it concurrently
    }
    if (!isNew || mentor == null || description == null) return 0;
    return notifications.notify(mentor.getUserId(), nType, title, description, link(s), "SIG:" + id, priority, s.getId()) ? 1 : 0;
  }

  private void resolve(Long studentId, String type) {
    jdbc.update("update support_signals set status = 'RESOLVED', resolved_at = now(), last_updated_at = now() where student_id = ? and signal_type = ? and status = 'ACTIVE'", studentId, type);
  }

  public List<SignalRow> signalsFor(Long studentId, boolean activeOnly) {
    return jdbc.query("select * from support_signals where student_id = ? " + (activeOnly ? "and status = 'ACTIVE' " : "") + "order by last_updated_at desc, id desc", this::row, studentId);
  }

  public List<SignalRow> activeSignals(Collection<Long> studentIds) {
    if (studentIds.isEmpty()) return List.of();
    String in = studentIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    return jdbc.query("select * from support_signals where status = 'ACTIVE' and student_id in (" + in + ") order by student_id, id", this::row);
  }

  private SignalRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    Timestamp res = rs.getTimestamp("resolved_at");
    return new SignalRow(rs.getLong("id"), rs.getLong("student_id"), rs.getString("signal_type"), rs.getString("status"), rs.getString("priority"), rs.getString("title"),
        rs.getString("summary"), read(rs.getString("details")), rs.getTimestamp("first_detected_at").toInstant(), rs.getTimestamp("last_updated_at").toInstant(), res == null ? null : res.toInstant());
  }

  private String priority(String key, String def) {
    String v = settings.get(key, def).toUpperCase();
    return List.of("NORMAL", "MEDIUM", "HIGH").contains(v) ? v : def;
  }

  private static String link(Student s) { return "/mentor/students/" + s.getId(); }
  private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }
  private static String pretty(String st) { return switch (st) { case "ATTENDED" -> "Attended"; case "ABSENT" -> "Absent"; default -> "Not conducted"; }; }
  private static String numberWord(int n) { String[] w = {"Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten"}; return n <= 10 ? w[n] : String.valueOf(n); }

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }
  private String write(Object o) { try { return json.writeValueAsString(o); } catch (Exception e) { return "{}"; } }
  private Map<String, Object> read(String s) {
    try { return json.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { return new LinkedHashMap<>(); }
  }
}
