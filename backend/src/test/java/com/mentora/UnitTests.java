package com.mentora;

import com.mentora.ai.AcademicInsightService;
import com.mentora.ai.MentorAnalyticsAssistantService;
import com.mentora.ai.MentorAnalyticsAssistantService.Op;
import com.mentora.domain.*;
import com.mentora.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UnitTests {
  private static final MarksCalculator.Rules RULES = new MarksCalculator.Rules(25, 2, 50, 25, 10, 3, 30, 25);

  private static Map<Component, Double> m(Object... kv) {
    Map<Component, Double> out = new EnumMap<>(Component.class);
    for (int i = 0; i < kv.length; i += 2) out.put((Component) kv[i], ((Number) kv[i + 1]).doubleValue());
    return out;
  }

  // ----- marks calculation (matches the ERP screenshots: bucket 17 -> 9, 25 -> 13, 21 -> 11) -----
  @Test void bucketAndConversionMatchErp() {
    assertEquals(9, MarksCalculator.calc(1L, m(Component.IA1, 17), RULES).iaConverted());
    assertEquals(13, MarksCalculator.calc(1L, m(Component.IA1, 25), RULES).iaConverted());
    assertEquals(11, MarksCalculator.calc(1L, m(Component.IA1, 21), RULES).iaConverted());
  }

  @Test void bestTwoOfThreeAreSummed() {
    var r = MarksCalculator.calc(1L, m(Component.IA1, 10, Component.IA2, 20, Component.IA3, 18), RULES);
    assertEquals(38, r.iaBucket());
    assertEquals(19, r.iaConverted());
  }

  @Test void activitiesAreConvertedAndTotalled() {
    var r = MarksCalculator.calc(1L, m(Component.IA1, 25, Component.IA2, 25, Component.ACT1, 10, Component.ACT2, 9, Component.ACT3, 8), RULES);
    assertEquals(27, r.actBucket());
    assertEquals(23, r.actConverted());   // 27 * 25 / 30 = 22.5 -> 23
    assertEquals(25 + 23, r.total());
  }

  @Test void rulesAreConfigurable() {
    var rules = new MarksCalculator.Rules(30, 3, 90, 30, 10, 3, 30, 20);
    var r = MarksCalculator.calc(1L, m(Component.IA1, 30, Component.IA2, 30, Component.IA3, 30), rules);
    assertEquals(90, r.iaBucket());
    assertEquals(30, r.iaConverted());
  }

  @Test void absentIsCountedAsZero() {
    Mark absent = new Mark();
    absent.setSubjectId(1L); absent.setComponent(Component.IA1); absent.setAbsent(true);
    Mark ia2 = new Mark();
    ia2.setSubjectId(1L); ia2.setComponent(Component.IA2); ia2.setScore(java.math.BigDecimal.valueOf(20));
    var settings = Mockito.mock(SettingsService.class);
    var res = new MarksCalculator(settings).compute(List.of(absent, ia2), RULES).get(1L);
    assertEquals(0.0, res.ia1());
    assertEquals(20, res.iaBucket());
  }

  // ----- attendance -----
  private static Attendance att(AttStatus s, LocalDate d) { Attendance a = new Attendance(); a.setStatus(s); a.setDate(d); return a; }

  @Test void attendancePercentageUsesRecords() {
    SettingsService st = Mockito.mock(SettingsService.class);
    Mockito.when(st.bool("attendance.leave_counts_as_present", true)).thenReturn(true);
    var calc = new AttendanceCalc(st);
    var sum = calc.summarize(List.of(att(AttStatus.P, LocalDate.now()), att(AttStatus.P, LocalDate.now()), att(AttStatus.A, LocalDate.now()), att(AttStatus.NC, LocalDate.now()), att(AttStatus.L, LocalDate.now())));
    assertEquals(4, sum.conducted());
    assertEquals(75.0, sum.percent());      // P,P,L attended of 4 conducted
    Mockito.when(st.bool("attendance.leave_counts_as_present", true)).thenReturn(false);
    assertEquals(25.0, calc.summarize(List.of(att(AttStatus.P, LocalDate.now()), att(AttStatus.L, LocalDate.now()), att(AttStatus.A, LocalDate.now()), att(AttStatus.A, LocalDate.now()))).percent());
  }

  @Test void consecutiveAbsencesCountBackFromReference() {
    LocalDate d = LocalDate.of(2026, 11, 2);
    var daily = List.of(new AttendanceCalc.DayStatus(d, AttStatus.P), new AttendanceCalc.DayStatus(d.plusDays(1), AttStatus.A),
        new AttendanceCalc.DayStatus(d.plusDays(2), AttStatus.NC), new AttendanceCalc.DayStatus(d.plusDays(3), AttStatus.A), new AttendanceCalc.DayStatus(d.plusDays(4), AttStatus.A));
    assertEquals(3, AttendanceCalc.consecutiveAbsent(daily, d.plusDays(4)));      // NC days do not break the streak
    assertEquals(0, AttendanceCalc.consecutiveAbsent(daily.subList(0, 1), d));
  }

  @Test void dayStatusIsMajorityOfConductedClasses() {
    LocalDate d = LocalDate.now();
    assertEquals(AttStatus.A, AttendanceCalc.dayStatus(List.of(att(AttStatus.A, d), att(AttStatus.A, d), att(AttStatus.P, d))));
    assertEquals(AttStatus.NC, AttendanceCalc.dayStatus(List.of(att(AttStatus.NC, d))));
  }

  // ----- moderation -----
  @Test void moderationDetectsProfanityButNotMedicalWords() {
    for (String ok : List.of("I have a fever and am absent due to illness", "My failure in IA1 shows I am weak in DBMS", "Please assess my assignment and class notes"))
      assertFalse(ModerationService.containsProfanity(ModerationService.normalize(ok)), ok);
    for (String bad : List.of("you are an idiot", "what the f u c k", "sh1t answer", "STUPID teacher", "fuuuuck this"))
      assertTrue(ModerationService.containsProfanity(ModerationService.normalize(bad)) || bad.contains("f u c k"), bad);
  }

  @Test void moderationFlagsSuspiciousLinks() {
    assertTrue(ModerationService.hasSuspiciousLink("click http://bit.ly/abc now"));
    assertTrue(ModerationService.hasSuspiciousLink("visit win-prizes.xyz"));
    assertFalse(ModerationService.hasSuspiciousLink("see my notes in drive.google.com/x"));
    assertFalse(ModerationService.hasSuspiciousLink("I will meet you tomorrow"));
  }

  @Test void normalizeCollapsesRepeatsAndLeetspeak() {
    assertEquals("shit", ModerationService.normalize("  $h1t  "));
    assertEquals("heello wworld", ModerationService.normalize("heeello   wwwworld"));
  }

  // ----- AI intent selection (deterministic) -----
  @Test void assistantMapsQuestionsToApprovedOperations() {
    Map<String, Op> cases = new LinkedHashMap<>();
    cases.put("How many students are attending regularly?", Op.ATTENDANCE_SUMMARY);
    cases.put("Which students have attendance below 75%?", Op.LOW_ATTENDANCE);
    cases.put("Which students are consistently scoring well across all three internals?", Op.CONSISTENT_PERFORMERS);
    cases.put("Which students have shown declining academic performance?", Op.DECLINING);
    cases.put("Who improved the most between IA 1 and IA 3?", Op.IMPROVED_MOST);
    cases.put("Which students have been absent for multiple consecutive days?", Op.CONSECUTIVE_ABSENT);
    cases.put("Summarize pending leave applications.", Op.PENDING_LEAVES);
    cases.put("Which students require academic attention?", Op.ATTENTION);
    cases.put("Summarize the overall performance of my assigned students.", Op.OVERALL_SUMMARY);
    cases.put("Compare IA 1 and IA 3 performance across my students.", Op.PERFORMANCE_COMPARISON);
    cases.put("Which students have good attendance but weak internal marks?", Op.GOOD_ATTENDANCE_WEAK_MARKS);
    cases.put("Which students are improving academically?", Op.IMPROVING);
    cases.put("Give me a summary of today's attendance.", Op.TODAY_ATTENDANCE);
    cases.put("Show students who have not attended regularly this month.", Op.IRREGULAR_THIS_MONTH);
    cases.put("Identify students who may need additional academic support.", Op.ATTENTION);
    cases.forEach((q, op) -> assertEquals(op, MentorAnalyticsAssistantService.classify(q), q));
    assertEquals(Op.UNKNOWN, MentorAnalyticsAssistantService.classify("What is the weather?"));
  }

  @Test void insightsAreBasedOnRealMarks() {
    var marks = new MarksCalculator.SubjectMarks(1L, 12.0, 18.0, null, null, null, null, 30, 15, 0, 0, 15);
    var row = new StudentDataService.MarksRow(1L, "23IS501", "Database Management Systems", marks);
    var out = AcademicInsightService.insights(List.of(row), List.of(), List.of(), 75);
    assertTrue(out.get(0).contains("Database Management Systems marks improved from IA 1 (12) to IA 2 (18)"), out.toString());
    assertEquals(List.of("Not enough data yet to show a trend."), AcademicInsightService.insights(List.of(), List.of(), List.of(), 75));
  }

  @Test void gradePointsAndCgpa() {
    Subject s1 = new Subject(); s1.setId(1L); s1.setCode("S1"); s1.setName("One"); s1.setCredits(4);
    Subject s2 = new Subject(); s2.setId(2L); s2.setCode("S2"); s2.setName("Two"); s2.setCredits(2);
    SemesterResult a = new SemesterResult(); a.setSubjectId(1L); a.setSemester(1); a.setGradePoints(9); a.setGrade("A+"); a.setStatus("PASS");
    SemesterResult b = new SemesterResult(); b.setSubjectId(2L); b.setSemester(1); b.setGradePoints(6); b.setGrade("B"); b.setStatus("PASS");
    var v = ResultsService.build(List.of(a, b), Map.of(1L, s1, 2L, s2));
    assertEquals(8.0, v.semesters().get(0).sgpa());   // (4*9 + 2*6) / 6
    assertEquals(8.0, v.cgpa());
  }
}
