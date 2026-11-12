package com.mentora.service;

import com.mentora.domain.*;
import com.mentora.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read models for a single student, shared by the student dashboard and the mentor's student profile. Caller must authorize first. */
@Service
public class StudentDataService {
  private final StudentRepository students;
  private final UserRepository users;
  private final DepartmentRepository departments;
  private final AttendanceRepository attendance;
  private final MarkRepository marks;
  private final SubjectRepository subjects;
  private final AttendanceCalc calc;
  private final MarksCalculator marksCalc;
  private final ResultsService resultsService;
  private final AccessService access;
  private final SettingsService settings;
  private final AnalyticsService analytics;

  public StudentDataService(StudentRepository students, UserRepository users, DepartmentRepository departments, AttendanceRepository attendance,
                            MarkRepository marks, SubjectRepository subjects, AttendanceCalc calc, MarksCalculator marksCalc,
                            ResultsService resultsService, AccessService access, SettingsService settings, AnalyticsService analytics) {
    this.students = students; this.users = users; this.departments = departments; this.attendance = attendance; this.marks = marks;
    this.subjects = subjects; this.calc = calc; this.marksCalc = marksCalc; this.resultsService = resultsService; this.access = access;
    this.settings = settings; this.analytics = analytics;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> profile(Long studentId) {
    Student s = students.findById(studentId).orElseThrow();
    User u = users.findById(s.getUserId()).orElseThrow();
    Faculty mentor = access.mentorOf(studentId);
    String mentorName = mentor == null ? null : users.findById(mentor.getUserId()).map(User::getFullName).orElse(null);
    var m = analytics.metrics(List.of(studentId)).get(0);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("studentId", s.getId());
    out.put("name", u.getFullName());
    out.put("email", u.getEmail());
    out.put("phone", u.getPhone());
    out.put("usn", s.getUsn());
    out.put("semester", s.getSemester());
    out.put("section", s.getSection());
    out.put("department", departments.findById(s.getDepartmentId()).map(Department::getName).orElse(""));
    out.put("mentorName", mentorName);
    out.put("cgpa", m.cgpa());
    out.put("attendancePct", m.attendancePct());
    out.put("status", m.status());
    out.put("flags", m.flags());
    return out;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> attendanceView(Long studentId, String month, Long subjectId) {
    List<Attendance> all = attendance.findByStudentIdOrderByDate(studentId);
    Map<Long, Subject> subj = resultsService.subjectMap();
    List<Attendance> scoped = all;
    if (month != null && !month.isBlank()) {
      YearMonth ym = YearMonth.parse(month);
      scoped = scoped.stream().filter(a -> YearMonth.from(a.getDate()).equals(ym)).toList();
    }
    if (subjectId != null) scoped = scoped.stream().filter(a -> a.getSubjectId().equals(subjectId)).toList();
    List<Map<String, Object>> subjectWise = new ArrayList<>();
    Map<Long, List<Attendance>> bySubject = all.stream().collect(Collectors.groupingBy(Attendance::getSubjectId));
    bySubject.forEach((sid, l) -> {
      Subject sb = subj.get(sid);
      var sm = calc.summarize(l);
      subjectWise.add(Map.of("subjectId", sid, "code", sb.getCode(), "name", sb.getName(), "present", sm.present() + sm.onDuty(), "absent", sm.absent(),
          "leave", sm.leave(), "conducted", sm.conducted(), "percent", sm.percent()));
    });
    subjectWise.sort(Comparator.comparing(m -> (String) m.get("code")));
    var daily = calc.daily(scoped);
    int workingDays = (int) daily.stream().filter(d -> d.status() != AttStatus.NC).count();
    Map<AttStatus, Long> dayCounts = daily.stream().collect(Collectors.groupingBy(AttendanceCalc.DayStatus::status, Collectors.counting()));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("overall", calc.summarize(all));
    out.put("filtered", calc.summarize(scoped));
    out.put("threshold", settings.num("attendance.threshold", 75));
    out.put("workingDays", workingDays);
    out.put("presentDays", dayCounts.getOrDefault(AttStatus.P, 0L));
    out.put("absentDays", dayCounts.getOrDefault(AttStatus.A, 0L));
    out.put("onDutyDays", dayCounts.getOrDefault(AttStatus.OD, 0L));
    out.put("leaveDays", dayCounts.getOrDefault(AttStatus.L, 0L));
    out.put("daily", daily);
    out.put("subjectWise", subjectWise);
    out.put("subjectRecords", subjectId == null ? List.of() : scoped.stream().map(a -> Map.of("date", a.getDate(), "status", a.getStatus())).toList());
    return out;
  }

  public record MarksRow(Long subjectId, String code, String name, MarksCalculator.SubjectMarks marks) {}

  @Transactional(readOnly = true)
  public Map<String, Object> marksView(Long studentId) {
    return Map.of("rows", marksRows(marks.findByStudentId(studentId)), "rules", marksCalc.rules());
  }

  public List<MarksRow> marksRows(Collection<Mark> ms) {
    Map<Long, Subject> subj = resultsService.subjectMap();
    var computed = marksCalc.compute(ms, marksCalc.rules());
    return computed.values().stream().map(c -> new MarksRow(c.subjectId(), subj.get(c.subjectId()).getCode(), subj.get(c.subjectId()).getName(), c))
        .sorted(Comparator.comparing(MarksRow::code)).toList();
  }

  @Transactional(readOnly = true)
  public Object results(Long studentId) { return resultsService.forStudent(studentId); }

  /** Chart-ready analytics: all values derived from stored records. */
  @Transactional(readOnly = true)
  public Map<String, Object> analytics(Long studentId) {
    List<MarksRow> rows = marksRows(marks.findByStudentId(studentId));
    List<Attendance> all = attendance.findByStudentIdOrderByDate(studentId);
    Map<String, List<Attendance>> byWeek = new TreeMap<>();
    for (Attendance a : all) {
      String key = a.getDate().get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", a.getDate().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
      byWeek.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
    }
    List<Map<String, Object>> trend = new ArrayList<>();
    byWeek.forEach((w, l) -> {
      var sm = calc.summarize(l);
      if (sm.conducted() > 0) trend.add(Map.of("week", w, "label", l.stream().map(Attendance::getDate).min(LocalDate::compareTo).get().toString().substring(5), "percent", sm.percent()));
    });
    var rv = resultsService.forStudent(studentId);
    List<Map<String, Object>> sgpa = new ArrayList<>();
    double pts = 0; int cr = 0;
    for (var b : rv.semesters()) {
      pts += b.sgpa() * b.credits(); cr += b.credits();
      sgpa.add(Map.of("semester", "Sem " + b.semester(), "sgpa", b.sgpa(), "cgpa", cr == 0 ? 0 : ResultsService.round2(pts / cr)));
    }
    List<Map<String, Object>> iaComparison = rows.stream().map(r -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("subject", r.code()); m.put("name", r.name());
      m.put("IA1", r.marks().ia1()); m.put("IA2", r.marks().ia2()); m.put("IA3", r.marks().ia3());
      m.put("total", r.marks().total());
      return m;
    }).toList();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("iaComparison", iaComparison);
    out.put("attendanceTrend", trend);
    out.put("sgpaProgression", sgpa);
    out.put("insights", com.mentora.ai.AcademicInsightService.insights(rows, trend, sgpa, settings.num("attendance.threshold", 75)));
    return out;
  }
}
