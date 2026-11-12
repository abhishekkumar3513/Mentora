package com.mentora.service;

import com.mentora.domain.*;
import com.mentora.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Computes per-student academic metrics in bulk (no N+1) for the mentor dashboard, tables and AI analytics operations. */
@Service
public class AnalyticsService {
  public record StudentMetrics(Long studentId, String name, String usn, int semester, double attendancePct, int classesConducted,
                               String todayStatus, int consecutiveAbsent, Double ia1, Double ia2, Double ia3, Double cgpa,
                               long pendingLeaves, Instant lastMessage, boolean lowAttendance, boolean declining, boolean improving,
                               boolean consistentlyGood, boolean weak, boolean goodAttendanceWeakMarks, Double improvementPercent,
                               List<String> flags, String status) {
    public boolean needsAttention() { return !flags.isEmpty(); }
  }

  private final StudentRepository students;
  private final UserRepository users;
  private final AttendanceRepository attendance;
  private final MarkRepository marks;
  private final SemesterResultRepository results;
  private final LeaveRequestRepository leaves;
  private final MessageRepository messages;
  private final SettingsService settings;
  private final AttendanceCalc calc;
  private final ResultsService resultsService;

  public AnalyticsService(StudentRepository students, UserRepository users, AttendanceRepository attendance, MarkRepository marks,
                          SemesterResultRepository results, LeaveRequestRepository leaves, MessageRepository messages,
                          SettingsService settings, AttendanceCalc calc, ResultsService resultsService) {
    this.students = students; this.users = users; this.attendance = attendance; this.marks = marks; this.results = results;
    this.leaves = leaves; this.messages = messages; this.settings = settings; this.calc = calc; this.resultsService = resultsService;
  }

  public LocalDate referenceDate() {
    LocalDate latest = attendance.latestDate();
    LocalDate now = LocalDate.now();
    return latest == null || latest.isAfter(now) ? now : latest;
  }

  @Transactional(readOnly = true)
  public List<StudentMetrics> metrics(Collection<Long> studentIds) {
    if (studentIds.isEmpty()) return List.of();
    List<Student> sts = students.findByIdIn(studentIds);
    Map<Long, User> userMap = users.findAllById(sts.stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    Map<Long, List<Attendance>> att = attendance.findByStudentIdIn(studentIds).stream().collect(Collectors.groupingBy(Attendance::getStudentId));
    Map<Long, List<Mark>> mk = marks.findByStudentIdIn(studentIds).stream().collect(Collectors.groupingBy(Mark::getStudentId));
    Map<Long, List<SemesterResult>> res = results.findByStudentIdIn(studentIds).stream().collect(Collectors.groupingBy(SemesterResult::getStudentId));
    Map<Long, Long> pending = new HashMap<>();
    leaves.findByStudentIdInAndStatus(studentIds, LeaveStatus.PENDING).forEach(l -> pending.merge(l.getStudentId(), 1L, Long::sum));
    Map<Long, Instant> lastMsg = new HashMap<>();
    messages.lastByStudent(studentIds).forEach(r -> lastMsg.put((Long) r[0], (Instant) r[1]));
    Map<Long, Subject> subj = resultsService.subjectMap();

    LocalDate ref = referenceDate();
    double threshold = settings.num("attendance.threshold", 75);
    int consecLimit = settings.integer("attendance.consecutive_absence_days", 2);
    double iaMax = settings.num("ia.max_per_test", 25);
    double good = settings.num("perf.good_percent", 80), weakPct = settings.num("perf.weak_percent", 50), decl = settings.num("perf.decline_percent", 8);

    List<StudentMetrics> out = new ArrayList<>();
    for (Student s : sts) {
      User u = userMap.get(s.getUserId());
      List<Attendance> a = att.getOrDefault(s.getId(), List.of());
      AttendanceCalc.Summary sum = calc.summarize(a);
      List<AttendanceCalc.DayStatus> daily = calc.daily(a);
      String today = daily.stream().filter(d -> d.date().equals(ref)).map(d -> d.status().name()).findFirst().orElse("NC");
      int consec = AttendanceCalc.consecutiveAbsent(daily, ref);
      Double[] ia = new Double[3];
      Map<Component, List<Double>> byComp = new EnumMap<>(Component.class);
      for (Mark m : mk.getOrDefault(s.getId(), List.of())) {
        if (m.getComponent().isIa()) byComp.computeIfAbsent(m.getComponent(), k -> new ArrayList<>()).add(m.isAbsent() || m.getScore() == null ? 0.0 : m.getScore().doubleValue());
      }
      Component[] cs = {Component.IA1, Component.IA2, Component.IA3};
      for (int i = 0; i < 3; i++) {
        List<Double> l = byComp.get(cs[i]);
        if (l != null && !l.isEmpty()) ia[i] = Math.round(l.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
      }
      List<Double> avail = Arrays.stream(ia).filter(Objects::nonNull).toList();
      boolean declining = false, improving = false, consistent = false, weak = false;
      Double improvement = null;
      if (!avail.isEmpty()) {
        double latest = avail.get(avail.size() - 1) / iaMax * 100;
        weak = latest < weakPct;
        consistent = avail.size() >= 2 && avail.stream().allMatch(v -> v / iaMax * 100 >= good);
        if (avail.size() >= 2) {
          double prev = avail.get(avail.size() - 2) / iaMax * 100;
          declining = prev - latest >= decl;
          improving = latest - prev >= decl;
          improvement = Math.round((latest - avail.get(0) / iaMax * 100) * 10) / 10.0;
        }
      }
      boolean low = sum.conducted() > 0 && sum.percent() < threshold;
      boolean goodAttWeak = sum.conducted() > 0 && sum.percent() >= threshold && !avail.isEmpty() && weak;
      List<String> flags = new ArrayList<>();
      if (low) flags.add("Low attendance (" + sum.percent() + "%)");
      if (consec >= consecLimit) flags.add(consec + " consecutive absences");
      if (declining) flags.add("Declining internal marks");
      if (weak) flags.add("Weak internal marks");
      long pend = pending.getOrDefault(s.getId(), 0L);
      ResultsService.ResultsView rv = ResultsService.build(res.getOrDefault(s.getId(), List.of()), subj);
      Double cgpa = rv.semesters().isEmpty() ? null : rv.cgpa();
      String status = !flags.isEmpty() ? "NEEDS ATTENTION" : consistent ? "EXCELLENT" : improving ? "IMPROVING" : "GOOD";
      out.add(new StudentMetrics(s.getId(), u == null ? "?" : u.getFullName(), s.getUsn(), s.getSemester(), sum.percent(), sum.conducted(), today, consec,
          ia[0], ia[1], ia[2], cgpa, pend, lastMsg.get(s.getId()), low, declining, improving, consistent, weak, goodAttWeak, improvement, flags, status));
    }
    out.sort(Comparator.comparing(StudentMetrics::usn));
    return out;
  }
}
