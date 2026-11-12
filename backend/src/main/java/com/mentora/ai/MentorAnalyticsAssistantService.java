package com.mentora.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.service.*;
import com.mentora.service.AnalyticsService.StudentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Mentor analytics assistant. Gemini never touches the database:
 * 1) the question is mapped to an approved operation (keywords first, Gemini classification only as a fallback),
 * 2) the operation runs validated queries scoped to the mentor's own students and computes every number in Java,
 * 3) only that verified, authorized data is sent to Gemini to phrase the answer, which is then validated.
 */
@Service
public class MentorAnalyticsAssistantService {
  private static final Logger log = LoggerFactory.getLogger(MentorAnalyticsAssistantService.class);
  private static final Pattern USN = Pattern.compile("\\b1[A-Z]{2}\\d{2}[A-Z]{2}\\d{3}\\b");

  public enum Op { ATTENDANCE_SUMMARY, LOW_ATTENDANCE, CONSISTENT_PERFORMERS, DECLINING, IMPROVED_MOST, IMPROVING, CONSECUTIVE_ABSENT, PENDING_LEAVES,
    PERFORMANCE_COMPARISON, ATTENTION, OVERALL_SUMMARY, GOOD_ATTENDANCE_WEAK_MARKS, TODAY_ATTENDANCE, IRREGULAR_THIS_MONTH, UNKNOWN }

  public record Card(String label, String value, String tone) {}
  public record Table(List<String> columns, List<List<String>> rows) {}
  public record Reply(String answer, boolean aiGenerated, String operation, List<Card> cards, Table table, String notice) {}
  record OpResult(String baseAnswer, List<Card> cards, Table table) {}

  private final AccessService access;
  private final AnalyticsService analytics;
  private final AttendanceRepository attendance;
  private final LeaveRequestRepository leaves;
  private final StudentRepository students;
  private final UserRepository users;
  private final AttendanceCalc calc;
  private final SettingsService settings;
  private final GeminiService gemini;
  private final ObjectMapper mapper;

  public MentorAnalyticsAssistantService(AccessService access, AnalyticsService analytics, AttendanceRepository attendance, LeaveRequestRepository leaves,
                                         StudentRepository students, UserRepository users, AttendanceCalc calc, SettingsService settings, GeminiService gemini, ObjectMapper mapper) {
    this.access = access; this.analytics = analytics; this.attendance = attendance; this.leaves = leaves; this.students = students; this.users = users;
    this.calc = calc; this.settings = settings; this.gemini = gemini; this.mapper = mapper;
  }

  // ---------- intent selection ----------
  public static Op classify(String q) {
    String s = q.toLowerCase();
    boolean att = s.contains("attend") || s.contains("absent") || s.contains("absence");
    if (s.contains("pending") && (s.contains("leave") || s.contains("request") || s.contains("application"))) return Op.PENDING_LEAVES;
    if (s.contains("consecutive") || (s.contains("absent") && (s.contains("multiple") || s.contains("several") || s.contains("many days")))) return Op.CONSECUTIVE_ABSENT;
    if ((s.contains("good attendance") || s.contains("high attendance") || s.contains("attend regularly") && s.contains("weak")) && (s.contains("weak") || s.contains("low") || s.contains("poor")))
      return Op.GOOD_ATTENDANCE_WEAK_MARKS;
    if (s.contains("today")) return Op.TODAY_ATTENDANCE;
    if (s.contains("this month") || s.contains("not attended regularly") || s.contains("irregular")) return Op.IRREGULAR_THIS_MONTH;
    if (s.contains("below") || s.contains("low attendance") || s.contains("shortage") || s.contains("less than") || s.contains("75%")) return Op.LOW_ATTENDANCE;
    if (s.contains("improved the most") || s.contains("most improved") || s.contains("improved most")) return Op.IMPROVED_MOST;
    if (s.contains("declin") || s.contains("dropp") || s.contains("deterior")) return Op.DECLINING;
    if (s.contains("improv")) return Op.IMPROVING;
    if (s.contains("consistent") || s.contains("scoring well") || s.contains("top perform") || s.contains("high perform")) return Op.CONSISTENT_PERFORMERS;
    if (s.contains("compare") || s.contains("comparison")) return Op.PERFORMANCE_COMPARISON;
    if (s.contains("attention") || s.contains("support") || s.contains("weak") || s.contains("struggl") || s.contains("at risk") || s.contains("need help")) return Op.ATTENTION;
    if (att && (s.contains("regular") || s.contains("how many") || s.contains("analy") || s.contains("summar") || s.contains("overview"))) return Op.ATTENDANCE_SUMMARY;
    if (s.contains("summar") || s.contains("overall") || s.contains("performance")) return Op.OVERALL_SUMMARY;
    if (att) return Op.ATTENDANCE_SUMMARY;
    return Op.UNKNOWN;
  }

  private Op classifyWithFallback(String q) {
    Op op = classify(q);
    if (op != Op.UNKNOWN || !gemini.isConfigured()) return op;
    String allowed = Arrays.stream(Op.values()).filter(o -> o != Op.UNKNOWN).map(Enum::name).collect(Collectors.joining(", "));
    Optional<String> raw = gemini.generateJson("Classify a mentor's question about their students into exactly one operation from this list: " + allowed
        + ", or UNKNOWN if none fits. Respond as JSON: {\"operation\":\"<NAME>\"}.", q);
    try {
      if (raw.isPresent()) return Op.valueOf(mapper.readTree(raw.get()).path("operation").asText("UNKNOWN"));
    } catch (Exception ignored) { }
    return Op.UNKNOWN;
  }

  // ---------- main entry ----------
  public Reply ask(UserPrincipal p, String question) {
    Faculty f = access.facultyOf(p);
    List<Long> ids = access.assignedStudentIds(f);
    Op op = classifyWithFallback(question);
    if (op == Op.UNKNOWN) {
      return new Reply("I can answer questions about attendance, internal marks, leave requests and students needing attention for your assigned students. "
          + "Try: \"Which students have attendance below 75%?\" or \"Who improved the most between IA 1 and IA 3?\"", false, op.name(), List.of(), null, null);
    }
    if (ids.isEmpty()) return new Reply("You currently have no assigned students, so there is nothing to analyze.", false, op.name(), List.of(), null, null);
    List<StudentMetrics> ms = analytics.metrics(ids);
    OpResult r = switch (op) {
      case ATTENDANCE_SUMMARY -> attendanceSummary(ms);
      case LOW_ATTENDANCE -> lowAttendance(ms);
      case CONSISTENT_PERFORMERS -> consistent(ms);
      case DECLINING -> declining(ms);
      case IMPROVED_MOST -> improvedMost(ms);
      case IMPROVING -> improving(ms);
      case CONSECUTIVE_ABSENT -> consecutive(ms);
      case PENDING_LEAVES -> pendingLeaves(ids);
      case PERFORMANCE_COMPARISON -> comparison(ms);
      case ATTENTION -> attention(ms);
      case OVERALL_SUMMARY -> overall(ms);
      case GOOD_ATTENDANCE_WEAK_MARKS -> goodAttWeak(ms);
      case TODAY_ATTENDANCE -> today(ms);
      case IRREGULAR_THIS_MONTH -> irregularThisMonth(ids, ms);
      default -> new OpResult("No data.", List.of(), null);
    };
    return narrate(question, op, r, ms);
  }


  private Reply narrate(String question, Op op, OpResult r, List<StudentMetrics> ms) {
    String notice = null;
    if (!gemini.isConfigured()) notice = "AI narration is unavailable (Gemini API key not configured). Showing verified data computed from your records.";
    else {
      Set<String> allowedUsn = ms.stream().map(StudentMetrics::usn).collect(Collectors.toSet());
      Set<String> allowedNames = ms.stream().map(m -> m.name().toLowerCase()).collect(Collectors.toSet());
      try {
        String data = mapper.writeValueAsString(Map.of("verifiedSummary", r.baseAnswer(), "cards", r.cards(), "table", r.table() == null ? "none" : r.table()));
        Optional<String> out = gemini.generateText(
            "You are the AI Mentor Assistant in a college mentoring app. Answer the mentor's question using ONLY the verified data supplied. "
                + "Never invent students, names, USNs, marks or percentages; if the data does not contain the answer, say so. "
                + "Be concise (under 130 words). Start with the answer, then if helpful add a short 'Suggested actions' line clearly marked as a recommendation. Plain text, no markdown tables.",
            "Mentor question: " + question + "\nVerified data (JSON):\n" + data);
        if (out.isPresent() && valid(out.get(), allowedUsn, allowedNames)) return new Reply(out.get().trim(), true, op.name(), r.cards(), r.table(), null);
        notice = "AI narration was unavailable or failed validation. Showing verified data computed from your records.";
      } catch (Exception e) {
        log.warn("Assistant narration failed: {}", e.toString());
        notice = "AI narration failed. Showing verified data computed from your records.";
      }
    }
    return new Reply(r.baseAnswer(), false, op.name(), r.cards(), r.table(), notice);
  }

  /** Rejects narrations that mention USNs outside the mentor's own students or absurdly long text. */
  static boolean valid(String text, Set<String> allowedUsn, Set<String> allowedNames) {
    if (text.isBlank() || text.length() > 2500) return false;
    var m = USN.matcher(text.toUpperCase());
    while (m.find()) if (!allowedUsn.contains(m.group())) return false;
    return true;
  }

  // ---------- approved analytics operations (all numbers computed in Java) ----------
  private double thr() { return settings.num("attendance.threshold", 75); }

  private static String pct(double v) { return v + "%"; }
  private static String f1(Double v) { return v == null ? "—" : String.valueOf(v); }
  private static String names(List<StudentMetrics> l) { return l.stream().limit(10).map(m -> m.name() + " (" + m.usn() + ")").collect(Collectors.joining(", ")) + (l.size() > 10 ? ", ..." : ""); }

  private OpResult attendanceSummary(List<StudentMetrics> ms) {
    long regular = ms.stream().filter(m -> !m.lowAttendance() && m.classesConducted() > 0).count();
    long low = ms.stream().filter(StudentMetrics::lowAttendance).count();
    double avg = ms.stream().mapToDouble(StudentMetrics::attendancePct).average().orElse(0);
    avg = Math.round(avg * 10) / 10.0;
    List<List<String>> rows = ms.stream().sorted(Comparator.comparingDouble(StudentMetrics::attendancePct)).map(m -> List.of(m.name(), m.usn(), pct(m.attendancePct()), m.lowAttendance() ? "Below threshold" : "Regular")).toList();
    return new OpResult(regular + " of " + ms.size() + " assigned students meet the " + (int) thr() + "% attendance requirement; " + low + " are below it. The average attendance is " + avg + "%.",
        List.of(new Card("Assigned students", String.valueOf(ms.size()), "neutral"), new Card("Attending regularly", String.valueOf(regular), "good"),
            new Card("Below " + (int) thr() + "%", String.valueOf(low), low > 0 ? "bad" : "good"), new Card("Average attendance", pct(avg), "neutral")),
        new Table(List.of("Student", "USN", "Attendance", "Status"), rows));
  }

  private OpResult lowAttendance(List<StudentMetrics> ms) {
    List<StudentMetrics> l = ms.stream().filter(StudentMetrics::lowAttendance).sorted(Comparator.comparingDouble(StudentMetrics::attendancePct)).toList();
    String ans = l.isEmpty() ? "None of your assigned students are below the " + (int) thr() + "% attendance threshold."
        : l.size() + " student(s) are below the " + (int) thr() + "% threshold: " + names(l) + ".";
    return new OpResult(ans, List.of(new Card("Below threshold", String.valueOf(l.size()), l.isEmpty() ? "good" : "bad"), new Card("Threshold", pct(thr()), "neutral")),
        new Table(List.of("Student", "USN", "Attendance", "Consecutive absences"), l.stream().map(m -> List.of(m.name(), m.usn(), pct(m.attendancePct()), String.valueOf(m.consecutiveAbsent()))).toList()));
  }

  private OpResult consistent(List<StudentMetrics> ms) {
    List<StudentMetrics> l = ms.stream().filter(StudentMetrics::consistentlyGood).toList();
    double good = settings.num("perf.good_percent", 80);
    String ans = l.isEmpty() ? "No student currently scores at or above " + (int) good + "% in every internal assessment taken so far."
        : l.size() + " student(s) score at or above " + (int) good + "% of the maximum in every internal assessment taken: " + names(l) + ".";
    return new OpResult(ans, List.of(new Card("Consistently strong", String.valueOf(l.size()), "good"), new Card("Criterion", "≥ " + (int) good + "% in every IA", "neutral")),
        new Table(List.of("Student", "USN", "IA 1", "IA 2", "IA 3"), l.stream().map(m -> List.of(m.name(), m.usn(), f1(m.ia1()), f1(m.ia2()), f1(m.ia3()))).toList()));
  }

  private OpResult declining(List<StudentMetrics> ms) {
    List<StudentMetrics> l = ms.stream().filter(StudentMetrics::declining).toList();
    double d = settings.num("perf.decline_percent", 8);
    String ans = l.isEmpty() ? "No student shows a decline of " + (int) d + " percentage points or more between their latest two internal assessments."
        : l.size() + " student(s) dropped by at least " + (int) d + " percentage points between their latest two internals: " + names(l) + ".";
    return new OpResult(ans, List.of(new Card("Declining", String.valueOf(l.size()), l.isEmpty() ? "good" : "bad")),
        new Table(List.of("Student", "USN", "IA 1", "IA 2", "IA 3"), l.stream().map(m -> List.of(m.name(), m.usn(), f1(m.ia1()), f1(m.ia2()), f1(m.ia3()))).toList()));
  }

  private OpResult improving(List<StudentMetrics> ms) {
    List<StudentMetrics> l = ms.stream().filter(StudentMetrics::improving).toList();
    String ans = l.isEmpty() ? "No student shows a clear improvement between their latest two internal assessments."
        : l.size() + " student(s) improved between their latest two internals: " + names(l) + ".";
    return new OpResult(ans, List.of(new Card("Improving", String.valueOf(l.size()), "good")),
        new Table(List.of("Student", "USN", "IA 1", "IA 2", "IA 3"), l.stream().map(m -> List.of(m.name(), m.usn(), f1(m.ia1()), f1(m.ia2()), f1(m.ia3()))).toList()));
  }

  private OpResult improvedMost(List<StudentMetrics> ms) {
    List<StudentMetrics> l = ms.stream().filter(m -> m.improvementPercent() != null).sorted(Comparator.comparingDouble((StudentMetrics m) -> m.improvementPercent()).reversed()).toList();
    if (l.isEmpty() || l.get(0).improvementPercent() <= 0) return new OpResult("No student has improved between their first and latest internal assessment yet.", List.of(), null);
    StudentMetrics top = l.get(0);
    return new OpResult(top.name() + " (" + top.usn() + ") improved the most, gaining " + top.improvementPercent() + " percentage points from the first to the latest internal assessment.",
        List.of(new Card("Top improver", top.name(), "good"), new Card("Gain", "+" + top.improvementPercent() + " pts", "good")),
        new Table(List.of("Student", "USN", "IA 1", "IA 2", "IA 3", "Change (pts)"), l.stream().limit(10).map(m -> List.of(m.name(), m.usn(), f1(m.ia1()), f1(m.ia2()), f1(m.ia3()), String.valueOf(m.improvementPercent()))).toList()));
  }

  private OpResult consecutive(List<StudentMetrics> ms) {
    int lim = settings.integer("attendance.consecutive_absence_days", 2);
    List<StudentMetrics> l = ms.stream().filter(m -> m.consecutiveAbsent() >= lim).sorted(Comparator.comparingInt(StudentMetrics::consecutiveAbsent).reversed()).toList();
    String ans = l.isEmpty() ? "No student has been absent for " + lim + " or more consecutive days." : l.size() + " student(s) have been absent for " + lim + "+ consecutive days: " + names(l) + ".";
    return new OpResult(ans, List.of(new Card("Consecutive absentees", String.valueOf(l.size()), l.isEmpty() ? "good" : "bad")),
        new Table(List.of("Student", "USN", "Consecutive absent days", "Attendance"), l.stream().map(m -> List.of(m.name(), m.usn(), String.valueOf(m.consecutiveAbsent()), pct(m.attendancePct()))).toList()));
  }

  private OpResult pendingLeaves(List<Long> ids) {
    List<LeaveRequest> l = leaves.findByStudentIdInAndStatus(ids, LeaveStatus.PENDING);
    Map<Long, Student> st = students.findByIdIn(ids).stream().collect(Collectors.toMap(Student::getId, Function.identity()));
    Map<Long, User> um = users.findAllById(st.values().stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    List<List<String>> rows = l.stream().sorted(Comparator.comparing(LeaveRequest::getCreatedAt)).map(x -> List.of(um.get(st.get(x.getStudentId()).getUserId()).getFullName(), st.get(x.getStudentId()).getUsn(),
        x.getType().name().replace('_', ' '), x.getStartDate() + " → " + x.getEndDate(), x.getReason().length() > 60 ? x.getReason().substring(0, 57) + "..." : x.getReason())).toList();
    String ans = l.isEmpty() ? "There are no pending leave applications." : l.size() + " leave application(s) are awaiting your review.";
    return new OpResult(ans, List.of(new Card("Pending leave requests", String.valueOf(l.size()), l.isEmpty() ? "good" : "warn")),
        new Table(List.of("Student", "USN", "Type", "Dates", "Reason"), rows));
  }

  private OpResult comparison(List<StudentMetrics> ms) {
    OptionalDouble a1 = ms.stream().filter(m -> m.ia1() != null).mapToDouble(StudentMetrics::ia1).average();
    OptionalDouble a2 = ms.stream().filter(m -> m.ia2() != null).mapToDouble(StudentMetrics::ia2).average();
    OptionalDouble a3 = ms.stream().filter(m -> m.ia3() != null).mapToDouble(StudentMetrics::ia3).average();
    List<Card> cards = new ArrayList<>();
    StringBuilder sb = new StringBuilder("Average internal marks per test across your students (out of " + (int) settings.num("ia.max_per_test", 25) + "): ");
    String[] n = {"IA 1", "IA 2", "IA 3"}; OptionalDouble[] av = {a1, a2, a3};
    for (int i = 0; i < 3; i++) {
      String v = av[i].isPresent() ? String.valueOf(Math.round(av[i].getAsDouble() * 10) / 10.0) : "not conducted";
      cards.add(new Card(n[i] + " average", v, "neutral")); sb.append(n[i]).append(" ").append(v).append(i < 2 ? ", " : ".");
    }
    List<List<String>> rows = ms.stream().map(m -> List.of(m.name(), m.usn(), f1(m.ia1()), f1(m.ia2()), f1(m.ia3()), m.improvementPercent() == null ? "—" : (m.improvementPercent() > 0 ? "+" : "") + m.improvementPercent())).toList();
    return new OpResult(sb.toString(), cards, new Table(List.of("Student", "USN", "IA 1", "IA 2", "IA 3", "Change (pts)"), rows));
  }

  private OpResult attention(List<StudentMetrics> ms) {
    List<StudentMetrics> l = ms.stream().filter(StudentMetrics::needsAttention).toList();
    String ans = l.isEmpty() ? "No student currently needs special attention based on attendance and internal marks."
        : l.size() + " student(s) need attention: " + names(l) + ".";
    return new OpResult(ans, List.of(new Card("Need attention", String.valueOf(l.size()), l.isEmpty() ? "good" : "bad"), new Card("Assigned students", String.valueOf(ms.size()), "neutral")),
        new Table(List.of("Student", "USN", "Attendance", "Latest IA", "Reasons"), l.stream().map(m -> List.of(m.name(), m.usn(), pct(m.attendancePct()),
            f1(m.ia3() != null ? m.ia3() : m.ia2() != null ? m.ia2() : m.ia1()), String.join("; ", m.flags()))).toList()));
  }

  private OpResult overall(List<StudentMetrics> ms) {
    double att = Math.round(ms.stream().mapToDouble(StudentMetrics::attendancePct).average().orElse(0) * 10) / 10.0;
    long attn = ms.stream().filter(StudentMetrics::needsAttention).count();
    long strong = ms.stream().filter(StudentMetrics::consistentlyGood).count();
    OptionalDouble cg = ms.stream().filter(m -> m.cgpa() != null).mapToDouble(StudentMetrics::cgpa).average();
    String cgs = cg.isPresent() ? String.valueOf(Math.round(cg.getAsDouble() * 100) / 100.0) : "n/a";
    return new OpResult("You have " + ms.size() + " assigned students with an average attendance of " + att + "%. " + strong + " are consistently strong in internals, and "
        + attn + " need attention. Average CGPA: " + cgs + ".",
        List.of(new Card("Students", String.valueOf(ms.size()), "neutral"), new Card("Average attendance", pct(att), "neutral"), new Card("Consistently strong", String.valueOf(strong), "good"),
            new Card("Need attention", String.valueOf(attn), attn > 0 ? "bad" : "good"), new Card("Average CGPA", cgs, "neutral")),
        new Table(List.of("Student", "USN", "Attendance", "IA 1", "IA 2", "IA 3", "CGPA", "Status"),
            ms.stream().map(m -> List.of(m.name(), m.usn(), pct(m.attendancePct()), f1(m.ia1()), f1(m.ia2()), f1(m.ia3()), f1(m.cgpa()), m.status())).toList()));
  }

  private OpResult goodAttWeak(List<StudentMetrics> ms) {
    List<StudentMetrics> l = ms.stream().filter(StudentMetrics::goodAttendanceWeakMarks).toList();
    String ans = l.isEmpty() ? "No student combines attendance above the threshold with weak internal marks."
        : l.size() + " student(s) attend regularly but have weak internal marks: " + names(l) + ".";
    return new OpResult(ans, List.of(new Card("Good attendance, weak marks", String.valueOf(l.size()), l.isEmpty() ? "good" : "warn")),
        new Table(List.of("Student", "USN", "Attendance", "IA 1", "IA 2", "IA 3"), l.stream().map(m -> List.of(m.name(), m.usn(), pct(m.attendancePct()), f1(m.ia1()), f1(m.ia2()), f1(m.ia3()))).toList()));
  }

  private OpResult today(List<StudentMetrics> ms) {
    LocalDate ref = analytics.referenceDate();
    Map<String, Long> c = ms.stream().collect(Collectors.groupingBy(StudentMetrics::todayStatus, Collectors.counting()));
    long p = c.getOrDefault("P", 0L) + c.getOrDefault("OD", 0L), a = c.getOrDefault("A", 0L), nc = c.getOrDefault("NC", 0L), l = c.getOrDefault("L", 0L);
    List<StudentMetrics> abs = ms.stream().filter(m -> m.todayStatus().equals("A")).toList();
    String ans = "Attendance for " + ref + ": " + p + " present/on duty, " + a + " absent, " + l + " on approved leave, " + nc + " with no class recorded." + (abs.isEmpty() ? "" : " Absent: " + names(abs) + ".");
    return new OpResult(ans, List.of(new Card("Present", String.valueOf(p), "good"), new Card("Absent", String.valueOf(a), a > 0 ? "bad" : "good"), new Card("On leave", String.valueOf(l), "neutral"), new Card("Date", ref.toString(), "neutral")),
        new Table(List.of("Student", "USN", "Status today"), ms.stream().filter(m -> !m.todayStatus().equals("P")).map(m -> List.of(m.name(), m.usn(), m.todayStatus())).toList()));
  }

  private OpResult irregularThisMonth(List<Long> ids, List<StudentMetrics> ms) {
    LocalDate ref = analytics.referenceDate();
    YearMonth ym = YearMonth.from(ref);
    Map<Long, List<Attendance>> byStudent = attendance.findByStudentIdInAndDateBetween(ids, ym.atDay(1), ym.atEndOfMonth()).stream().collect(Collectors.groupingBy(Attendance::getStudentId));
    List<List<String>> rows = new ArrayList<>();
    for (StudentMetrics m : ms) {
      var sum = calc.summarize(byStudent.getOrDefault(m.studentId(), List.of()));
      if (sum.conducted() > 0 && sum.percent() < thr()) rows.add(List.of(m.name(), m.usn(), pct(sum.percent()), String.valueOf(sum.absent())));
    }
    String ans = rows.isEmpty() ? "All students are meeting the " + (int) thr() + "% attendance requirement in " + ym + "."
        : rows.size() + " student(s) have attendance below " + (int) thr() + "% in " + ym + ".";
    return new OpResult(ans, List.of(new Card("Irregular this month", String.valueOf(rows.size()), rows.isEmpty() ? "good" : "bad"), new Card("Month", ym.toString(), "neutral")),
        new Table(List.of("Student", "USN", "Attendance this month", "Absent classes"), rows));
  }
}
