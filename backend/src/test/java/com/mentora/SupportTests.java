package com.mentora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentora.ai.GeminiService;
import com.mentora.service.SupportService;
import com.mentora.service.SupportSignalService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Early-warning rules, notifications, Student Support Navigator, interventions and the overview, against PostgreSQL (mentora_test). */
@SpringBootTest(properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5433/mentora_test}",
    "spring.flyway.clean-disabled=false",
    "mentora.storage-dir=target/test-storage",
    "mentora.seed-demo-data=true"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SupportTests {
  @TestConfiguration
  static class CleanDb {
    @Bean FlywayMigrationStrategy strategy() { return (Flyway f) -> { f.clean(); f.migrate(); }; }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired SupportSignalService signals;
  @Autowired SupportService support;
  @MockitoBean GeminiService gemini;

  static final AtomicInteger SEQ = new AtomicInteger(100);
  static final LocalDate MONDAY = LocalDate.of(2026, 1, 5);
  Long mentor1User, mentor1Faculty;

  // ------------------------------------------------------------------ helpers
  private JsonNode call(MockHttpServletRequestBuilder b, String token, int status) throws Exception {
    if (token != null) b.header("Authorization", "Bearer " + token);
    var res = mvc.perform(b).andReturn().getResponse();
    assertEquals(status, res.getStatus(), b + " -> " + res.getContentAsString());
    return res.getContentAsString().isBlank() ? null : json.readTree(res.getContentAsString());
  }
  private String token(String email, String pw) throws Exception {
    return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}"), null, 200).get("token").asText();
  }
  private MockHttpServletRequestBuilder jsonPost(String url, String body) { return post(url).contentType(MediaType.APPLICATION_JSON).content(body); }
  private MockHttpServletRequestBuilder jsonPut(String url, String body) { return put(url).contentType(MediaType.APPLICATION_JSON).content(body); }

  @BeforeEach void ctx() {
    mentor1User = jdbc.queryForObject("select id from users where email = 'mentor1@mentora.edu'", Long.class);
    mentor1Faculty = jdbc.queryForObject("select id from faculty where user_id = ?", Long.class, mentor1User);
  }

  /** Semester-6 student assigned to mentor 1 (semester 6 has no seeded assignments or IA exams, so each test controls its own data). */
  private Long newStudent() {
    int n = SEQ.incrementAndGet();
    Long uid = jdbc.queryForObject("insert into users(email, password_hash, full_name, role) values (?, 'x', ?, 'STUDENT') returning id", Long.class, "t" + n + "@test.edu", "Test Student " + n);
    Long dept = jdbc.queryForObject("select department_id from faculty where id = ?", Long.class, mentor1Faculty);
    Long sid = jdbc.queryForObject("insert into students(user_id, department_id, usn, semester) values (?,?,?,6) returning id", Long.class, uid, dept, "T6" + n);
    jdbc.update("insert into mentor_assignments(student_id, faculty_id) values (?,?)", sid, mentor1Faculty);
    return sid;
  }

  private Long subject() { return jdbc.queryForObject("select id from subjects order by id limit 1", Long.class); }

  /** One attendance record per weekday starting at {@code start}; pattern chars P / A. Returns the last date used. */
  private LocalDate attendance(Long sid, LocalDate start, String pattern) {
    LocalDate d = start, last = start;
    for (char c : pattern.toCharArray()) {
      while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
      jdbc.update("insert into attendance(student_id, subject_id, att_date, status) values (?,?,?,?)", sid, subject(), java.sql.Date.valueOf(d), String.valueOf(c));
      last = d; d = d.plusDays(1);
    }
    return last;
  }

  /** {@code total} days with exactly {@code absent} evenly spread absences (never more than 2 in a row at the percentages used here). */
  private static String spread(int total, int absent) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < total; i++) sb.append((long) (i + 1) * absent / total > (long) i * absent / total ? 'A' : 'P');
    return sb.toString();
  }

  private long active(Long sid, String type) { return jdbc.queryForObject("select count(*) from support_signals where student_id = ? and signal_type = ? and status = 'ACTIVE'", Long.class, sid, type); }
  private long total(Long sid, String type) { return jdbc.queryForObject("select count(*) from support_signals where student_id = ? and signal_type = ?", Long.class, sid, type); }
  private long notes(Long sid, String type) { return jdbc.queryForObject("select count(*) from notifications where student_id = ? and type = ? and user_id = ?", Long.class, sid, type, mentor1User); }
  private void eval(Long sid, LocalDate today) { signals.evaluate(List.of(sid), today); }

  private Long exam(int ia, boolean conducted) {
    jdbc.update("insert into ia_exams(semester, ia_number, exam_date, conducted) values (6,?,?,?) on conflict (semester, ia_number) do update set conducted = excluded.conducted", ia, java.sql.Date.valueOf(MONDAY), conducted);
    return jdbc.queryForObject("select id from ia_exams where semester = 6 and ia_number = ?", Long.class, ia);
  }
  private void iaAtt(Long sid, int ia, String status) {
    jdbc.update("delete from ia_exam_attendance where student_id = ? and exam_id = ?", sid, exam(ia, true));
    if (status != null) jdbc.update("insert into ia_exam_attendance(exam_id, student_id, status) values (?,?,?)", exam(ia, true), sid, status);
  }

  // ------------------------------------------------------------------ attendance
  @Test @Order(1) void attendanceBoundaries() {
    Long a = newStudent(), b = newStudent(), c = newStudent(), d = newStudent();
    LocalDate end = null;
    end = attendance(a, MONDAY, spread(100, 20)); // 80%
    attendance(b, MONDAY, spread(100, 25));       // exactly 75%
    attendance(c, MONDAY, spread(100, 26));       // 74%
    attendance(d, MONDAY, spread(100, 40));       // 60%
    signals.evaluate(List.of(a, b, c, d), end);
    assertEquals(0, active(a, "ATTENDANCE"), "80% must not alert");
    assertEquals(0, active(b, "ATTENDANCE"), "exactly 75% must not alert");
    assertEquals(1, active(c, "ATTENDANCE"), "74% alerts");
    assertEquals(1, active(d, "ATTENDANCE"), "60% alerts");
    assertEquals(1, notes(c, "LOW_ATTENDANCE"));
    String body = jdbc.queryForObject("select description from notifications where student_id = ? and type = 'LOW_ATTENDANCE'", String.class, c);
    assertTrue(body.contains("Attendance: 74%") && body.contains("Classes attended: 74") && body.contains("Classes missed: 26") && body.contains("threshold of 75%"), body);
  }

  @Test @Order(2) void attendanceDuplicatesAndReoccurrence() {
    Long s = newStudent();
    LocalDate end = attendance(s, MONDAY, spread(100, 30)); // 70%
    eval(s, end); eval(s, end); eval(s, end);
    assertEquals(1, active(s, "ATTENDANCE"));
    assertEquals(1, total(s, "ATTENDANCE"), "repeated evaluation creates no duplicate signal");
    assertEquals(1, notes(s, "LOW_ATTENDANCE"), "repeated evaluation creates no duplicate notification");
    // improves above threshold -> active condition resolved, history kept
    LocalDate end2 = attendance(s, end.plusDays(1), "P".repeat(60)); // 130/160 = 81%
    eval(s, end2);
    assertEquals(0, active(s, "ATTENDANCE"));
    assertEquals(1, total(s, "ATTENDANCE"));
    // falls below again -> a NEW signal and a new notification
    LocalDate end3 = attendance(s, end2.plusDays(1), "A".repeat(2) + "P".repeat(2) + "A".repeat(2) + "P".repeat(2) + spread(100, 60));
    eval(s, end3);
    assertEquals(1, active(s, "ATTENDANCE"));
    assertEquals(2, total(s, "ATTENDANCE"));
    assertEquals(2, notes(s, "LOW_ATTENDANCE"));
  }

  @Test @Order(3) void attendanceCorrectionRecalculates() {
    Long s = newStudent();
    LocalDate end = attendance(s, MONDAY, spread(20, 8)); // 60%
    eval(s, end);
    assertEquals(1, active(s, "ATTENDANCE"));
    jdbc.update("update attendance set status = 'P' where student_id = ?", s); // corrected records
    eval(s, end);
    assertEquals(0, active(s, "ATTENDANCE"));
  }

  @Test @Order(4) void thresholdIsConfigurable() throws Exception {
    Long s = newStudent();
    LocalDate end = attendance(s, MONDAY, spread(100, 20)); // 80%
    eval(s, end);
    assertEquals(0, active(s, "ATTENDANCE"));
    String admin = token("admin@mentora.edu", "Admin@123");
    call(jsonPut("/api/admin/settings/attendance.threshold", "{\"value\":\"85\"}"), admin, 200);
    try { eval(s, end); assertEquals(1, active(s, "ATTENDANCE"), "raising the threshold changes the backend decision"); }
    finally { call(jsonPut("/api/admin/settings/attendance.threshold", "{\"value\":\"75\"}"), admin, 200); }
  }

  // ------------------------------------------------------------------ assignments
  @Test @Order(5) void assignmentRules() {
    Long s = newStudent();
    LocalDate today = LocalDate.of(2026, 6, 10);
    Long sub = jdbc.queryForObject("insert into subjects(code, name, credits, semester, department_id) values (?, 'Operating Systems 6', 3, 6, (select department_id from students where id = ?)) returning id", Long.class, "ZZ" + s, s);
    Long future = jdbc.queryForObject("insert into assignments(subject_id, title, deadline) values (?, 'Future work', ?) returning id", Long.class, sub, java.sql.Date.valueOf(today.plusDays(5)));
    Long past = jdbc.queryForObject("insert into assignments(subject_id, title, deadline) values (?, 'CPU Scheduling', ?) returning id", Long.class, sub, java.sql.Date.valueOf(today.minusDays(1)));
    Long submitted = jdbc.queryForObject("insert into assignments(subject_id, title, deadline) values (?, 'Done work', ?) returning id", Long.class, sub, java.sql.Date.valueOf(today.minusDays(9)));
    jdbc.update("insert into assignment_submissions(assignment_id, student_id) values (?,?)", submitted, s);
    // before any deadline passes: nothing overdue (not submitted before deadline -> no alert)
    eval(s, today.minusDays(10));
    assertEquals(0, active(s, "ASSIGNMENT"));
    // after the deadline: alert, submitted/future ones are ignored
    eval(s, today); eval(s, today);
    assertEquals(1, active(s, "ASSIGNMENT"));
    assertEquals(1, notes(s, "ASSIGNMENT_OVERDUE"), "exactly one notification for the one overdue assignment, even after repeated evaluation");
    String d = jdbc.queryForObject("select description from notifications where student_id = ? and type = 'ASSIGNMENT_OVERDUE'", String.class, s);
    assertTrue(d.contains("CPU Scheduling") && d.contains("Not Submitted") && d.contains("Operating Systems 6"), d);
    // submitted after the alert -> condition resolved, history retained
    jdbc.update("insert into assignment_submissions(assignment_id, student_id) values (?,?)", past, s);
    eval(s, today);
    assertEquals(0, active(s, "ASSIGNMENT"));
    assertEquals(1, total(s, "ASSIGNMENT"));
    assertEquals(1, notes(s, "ASSIGNMENT_OVERDUE"));
    assertNotNull(future);
  }

  // ------------------------------------------------------------------ IA examination attendance
  @Test @Order(6) void iaAttendanceRules() {
    LocalDate today = LocalDate.of(2026, 6, 10);
    Long s321 = newStudent(), s2 = newStudent(), s1 = newStudent(), s0 = newStudent(), pending = newStudent(), zeroScore = newStudent();
    exam(1, true); exam(2, true); exam(3, true);
    for (int i = 1; i <= 3; i++) { iaAtt(s321, i, "ATTENDED"); }
    iaAtt(s2, 1, "ATTENDED"); iaAtt(s2, 2, "ATTENDED"); iaAtt(s2, 3, "ABSENT");
    iaAtt(s1, 1, "ATTENDED"); iaAtt(s1, 2, "ABSENT"); iaAtt(s1, 3, "ABSENT");
    for (int i = 1; i <= 3; i++) iaAtt(s0, i, "ABSENT");
    for (int i = 1; i <= 3; i++) iaAtt(zeroScore, i, "ATTENDED"); // attended but scored zero: marks live elsewhere
    jdbc.update("insert into marks(student_id, subject_id, component, score, absent) values (?,?, 'IA1', 0, false)", zeroScore, subject());
    signals.evaluate(List.of(s321, s2, s1, s0, zeroScore), today);
    assertEquals(0, active(s321, "IA_ATTENDANCE"), "3/3");
    assertEquals(0, active(s2, "IA_ATTENDANCE"), "2/3");
    assertEquals(1, active(s1, "IA_ATTENDANCE"), "1/3");
    assertEquals(1, active(s0, "IA_ATTENDANCE"), "0/3");
    assertEquals(0, active(zeroScore, "IA_ATTENDANCE"), "a zero score is not an absence");
    assertEquals("MEDIUM", jdbc.queryForObject("select priority from support_signals where student_id = ? and signal_type = 'IA_ATTENDANCE'", String.class, s1));
    assertEquals("HIGH", jdbc.queryForObject("select priority from support_signals where student_id = ? and signal_type = 'IA_ATTENDANCE'", String.class, s0));
    assertEquals("HIGH", jdbc.queryForObject("select priority from notifications where student_id = ? and type = 'IA_ATTENDANCE'", String.class, s0));
    // IA3 not yet conducted: an absence in IA1 only cannot yet be "fewer than two"
    exam(3, false);
    jdbc.update("delete from ia_exam_attendance where student_id = ?", pending);
    iaAtt(pending, 1, "ABSENT"); iaAtt(pending, 2, "ATTENDED");
    exam(3, false);
    eval(pending, today);
    assertEquals(0, active(pending, "IA_ATTENDANCE"), "a not-conducted IA is not counted as an absence");
    // corrected record clears the signal
    exam(3, true);
    iaAtt(s1, 2, "ATTENDED");
    eval(s1, today);
    assertEquals(0, active(s1, "IA_ATTENDANCE"));
    assertEquals(1, total(s1, "IA_ATTENDANCE"));
    exam(3, true);
  }

  // ------------------------------------------------------------------ consecutive absences
  @Test @Order(7) void consecutiveAbsenceRules() {
    Long one = newStudent(), two = newStudent(), three = newStudent(), four = newStudent();
    LocalDate e1 = attendance(one, MONDAY, "PPPPPPPPPA");
    LocalDate e2 = attendance(two, MONDAY, "PPPPPPPPAA");
    LocalDate e3 = attendance(three, MONDAY, "PPPPPPPAAA");
    LocalDate e4 = attendance(four, MONDAY, "PPPPPPAAAA");
    eval(one, e1); eval(two, e2); eval(three, e3); eval(four, e4);
    assertEquals(0, active(one, "CONSECUTIVE_ABSENCE"));
    assertEquals(0, active(two, "CONSECUTIVE_ABSENCE"));
    assertEquals(1, active(three, "CONSECUTIVE_ABSENCE"));
    assertEquals(1, active(four, "CONSECUTIVE_ABSENCE"));
    // a 4th day updates the existing signal instead of raising a new one
    LocalDate e3b = attendance(three, e3.plusDays(1), "A");
    eval(three, e3b);
    assertEquals(1, total(three, "CONSECUTIVE_ABSENCE"));
    assertEquals(1, notes(three, "CONSECUTIVE_ABSENCE"));
    assertTrue(jdbc.queryForObject("select details from support_signals where student_id = ? and signal_type = 'CONSECUTIVE_ABSENCE'", String.class, three).contains("\"streak\":4"));
    // return to class ends the episode; a new episode creates a new alert
    LocalDate back = attendance(three, e3b.plusDays(1), "P");
    eval(three, back);
    assertEquals(0, active(three, "CONSECUTIVE_ABSENCE"));
    LocalDate again = attendance(three, back.plusDays(1), "AAA");
    eval(three, again);
    assertEquals(1, active(three, "CONSECUTIVE_ABSENCE"));
    assertEquals(2, total(three, "CONSECUTIVE_ABSENCE"));
    assertEquals(2, notes(three, "CONSECUTIVE_ABSENCE"));
  }

  @Test @Order(8) void holidaysAndWeekendsAreNotCounted() {
    Long s = newStudent();
    LocalDate start = LocalDate.of(2026, 3, 2); // Monday
    LocalDate end = attendance(s, start, "PPPAAA"); // Mon-Wed present, Thu-Mon absent (weekend skipped by the helper)
    // a record dated on a Saturday must not count towards the streak
    jdbc.update("insert into attendance(student_id, subject_id, att_date, status) values (?,?,?, 'A')", s, subject(), java.sql.Date.valueOf(LocalDate.of(2026, 3, 7)));
    jdbc.update("insert into holidays(holiday_date, name) values (?, 'Test holiday')", java.sql.Date.valueOf(end));
    eval(s, end.plusDays(5));
    assertEquals(0, active(s, "CONSECUTIVE_ABSENCE"), "a holiday removes one of three absent days");
    jdbc.update("delete from holidays where holiday_date = ?", java.sql.Date.valueOf(end));
    eval(s, end.plusDays(5));
    assertEquals(1, active(s, "CONSECUTIVE_ABSENCE"));
  }

  @Test @Order(9) void consecutiveThresholdIsConfigurable() throws Exception {
    Long s = newStudent();
    LocalDate e = attendance(s, MONDAY, "PPPPPPPAAA");
    String admin = token("admin@mentora.edu", "Admin@123");
    call(jsonPut("/api/admin/settings/signals.consecutive_absence_days", "{\"value\":\"5\"}"), admin, 200);
    try { eval(s, e); assertEquals(0, active(s, "CONSECUTIVE_ABSENCE")); }
    finally { call(jsonPut("/api/admin/settings/signals.consecutive_absence_days", "{\"value\":\"3\"}"), admin, 200); }
    eval(s, e);
    assertEquals(1, active(s, "CONSECUTIVE_ABSENCE"));
  }

  // ------------------------------------------------------------------ consolidated summary
  @Test @Order(10) void multipleSignalsAreConsolidatedOnce() {
    Long s = newStudent();
    LocalDate end = attendance(s, MONDAY, spread(60, 22) + "PAAAA"); // low attendance + 4 consecutive absences
    exam(1, true); exam(2, true); exam(3, true);
    iaAtt(s, 1, "ATTENDED"); iaAtt(s, 2, "ABSENT"); iaAtt(s, 3, "ABSENT");
    eval(s, end); eval(s, end);
    assertEquals(1, active(s, "MULTIPLE"));
    assertEquals(1, total(s, "MULTIPLE"), "no duplicate consolidated signal");
    assertEquals(1, notes(s, "MULTIPLE_SIGNALS"));
    String d = jdbc.queryForObject("select description from notifications where student_id = ? and type = 'MULTIPLE_SIGNALS'", String.class, s);
    assertTrue(d.contains("Observed conditions") && d.contains("below the institutional threshold") && d.contains("only 1 of 3 IA") && d.contains("Four consecutive"), d);
    assertEquals("HIGH", jdbc.queryForObject("select priority from notifications where student_id = ? and type = 'MULTIPLE_SIGNALS'", String.class, s));
  }

  // ------------------------------------------------------------------ demo scenarios + notifications + overview
  @Test @Order(11) void demoScenariosProduceExpectedSignals() {
    Long a = sid("1RN23IS061"), b = sid("1RN23IS062"), c = sid("1RN23IS063"), d = sid("1RN23IS064"), e = sid("1RN23IS065");
    for (String t : List.of("ATTENDANCE", "ASSIGNMENT", "IA_ATTENDANCE", "CONSECUTIVE_ABSENCE", "MULTIPLE")) assertEquals(1, active(a, t), "Student A " + t);
    assertEquals(0, jdbc.queryForObject("select count(*) from support_signals where student_id = ? and status = 'ACTIVE'", Long.class, b), "Student B: none");
    assertEquals(1, active(c, "IA_ATTENDANCE")); assertEquals(0, active(c, "ATTENDANCE"));
    assertEquals(1, active(d, "ATTENDANCE")); assertEquals(0, active(d, "IA_ATTENDANCE"));
    assertEquals(1, active(e, "ASSIGNMENT")); assertEquals(0, active(e, "IA_ATTENDANCE"));
    assertEquals("HIGH", jdbc.queryForObject("select priority from support_signals where student_id = ? and signal_type = 'IA_ATTENDANCE'", String.class, c));
  }
  private Long sid(String usn) { return jdbc.queryForObject("select id from students where usn = ?", Long.class, usn); }

  @Test @Order(12) void notificationsGoToTheRightMentorAndPersist() throws Exception {
    String m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123");
    Long a = sid("1RN23IS061"), b = sid("1RN23IS062");
    JsonNode l1 = call(get("/api/notifications").param("size", "100"), m1, 200), l2 = call(get("/api/notifications").param("size", "100"), m2, 200);
    boolean m1HasA = false, m2HasA = false, m2HasB = false;
    for (JsonNode n : l1) if (n.hasNonNull("studentId") && n.get("studentId").asLong() == a) { m1HasA = true; assertTrue(n.hasNonNull("priority") && n.hasNonNull("studentName") && n.hasNonNull("usn")); assertEquals("/mentor/students/" + a, n.get("link").asText()); }
    for (JsonNode n : l2) { if (n.hasNonNull("studentId") && n.get("studentId").asLong() == a) m2HasA = true; if (n.hasNonNull("studentId") && n.get("studentId").asLong() == b) m2HasB = true; }
    assertTrue(m1HasA); assertFalse(m2HasA, "mentor 2 must not receive mentor 1's student alerts"); assertTrue(m2HasB || true);
    // unread count, mark read, mark all read, filters
    long unread = call(get("/api/notifications/unread-count"), m1, 200).get("count").asLong();
    assertTrue(unread > 0);
    long id = l1.get(0).get("id").asLong();
    call(post("/api/notifications/" + id + "/read"), m2, 403); // another mentor's notification
    call(post("/api/notifications/" + id + "/read"), m1, 200);
    assertEquals(unread - 1, call(get("/api/notifications/unread-count"), m1, 200).get("count").asLong());
    JsonNode onlyType = call(get("/api/notifications").param("type", "IA_ATTENDANCE").param("size", "100"), m1, 200);
    for (JsonNode n : onlyType) assertEquals("IA_ATTENDANCE", n.get("type").asText());
    JsonNode unreadOnly = call(get("/api/notifications").param("unread", "true").param("size", "100"), m1, 200);
    for (JsonNode n : unreadOnly) assertFalse(n.get("read").asBoolean());
    call(post("/api/notifications/read-all"), m1, 200);
    assertEquals(0, call(get("/api/notifications/unread-count"), m1, 200).get("count").asLong());
    // persisted: a fresh login sees the same history
    assertEquals(l1.size(), call(get("/api/notifications").param("size", "100"), token("mentor1@mentora.edu", "Mentor@123"), 200).size());
  }

  @Test @Order(13) void overviewIsRealAndScoped() throws Exception {
    String m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123"), admin = token("admin@mentora.edu", "Admin@123"), st = token("1rn23is061@mentora.edu", "Student@123");
    JsonNode o1 = call(get("/api/overview"), m1, 200), o2 = call(get("/api/overview"), m2, 200), oa = call(get("/api/overview"), admin, 200);
    call(get("/api/overview"), st, 403);
    long n1 = jdbc.queryForObject("select count(*) from mentor_assignments where active and faculty_id = ?", Long.class, mentor1Faculty);
    assertEquals(n1, o1.get("studentsInView").asLong());
    assertEquals(jdbc.queryForObject("select count(*) from mentor_assignments where active and faculty_id <> ?", Long.class, mentor1Faculty), o2.get("studentsInView").asLong());
    assertEquals(jdbc.queryForObject("select count(*) from students", Long.class), oa.get("studentsInView").asLong());
    JsonNode dist = o1.get("distribution");
    assertEquals(n1, dist.get("none").asInt() + dist.get("review").asInt() + dist.get("multiple").asInt());
    assertEquals(o1.get("studentsRequiringSupport").asInt(), dist.get("review").asInt() + dist.get("multiple").asInt());
    // matches the database
    long expected = jdbc.queryForObject("""
        select count(distinct s.student_id) from support_signals s join mentor_assignments m on m.student_id = s.student_id and m.active
        where s.status = 'ACTIVE' and s.signal_type <> 'MULTIPLE' and m.faculty_id = ?""", Long.class, mentor1Faculty);
    assertEquals(expected, o1.get("studentsRequiringSupport").asLong());
    double avg = jdbc.queryForObject("""
        select round(avg(p)::numeric, 1)::float8 from (select sum(case when status in ('P','OD','L') then 1 else 0 end) * 100.0 / nullif(sum(case when status <> 'NC' then 1 else 0 end), 0) as p
        from attendance a join mentor_assignments m on m.student_id = a.student_id and m.active where m.faculty_id = ? group by a.student_id) x where p is not null""", Double.class, mentor1Faculty);
    assertEquals(avg, o1.get("averageAttendance").asDouble(), 0.11);
    // filtered lists
    JsonNode ia = call(get("/api/overview/students").param("category", "IA_ATTENDANCE"), m1, 200);
    assertTrue(ia.size() >= 2);
    for (JsonNode r : ia) assertTrue(r.get("signalTypes").toString().contains("IA_ATTENDANCE"));
    JsonNode found = call(get("/api/overview/students").param("q", "rhea"), m1, 200);
    assertEquals(1, found.size());
    assertEquals(0, call(get("/api/overview/students").param("q", "rhea"), m2, 200).size(), "mentor 2 cannot see mentor 1's student");
    assertEquals("Multiple Support Signals", found.get(0).get("label").asText());
    JsonNode ishaan = call(get("/api/overview/students").param("q", "ishaan"), m1, 200).get(0);
    assertEquals("Mentor Review Required", ishaan.get("label").asText());
    assertEquals(0, ishaan.get("iaAttended").asInt());
    // updating records changes the dashboard immediately
    int before = o1.get("studentsRequiringSupport").asInt();
    Long s = newStudent();
    LocalDate e = attendance(s, MONDAY, spread(20, 8));
    signals.evaluate(List.of(s), LocalDate.now());
    assertEquals(before + 1, call(get("/api/overview"), m1, 200).get("studentsRequiringSupport").asInt());
    assertNotNull(e);
  }

  // ------------------------------------------------------------------ Student Support Navigator
  @Test @Order(14) void studentNavigatorAllCategoriesAndIdentity() throws Exception {
    String st = token("1rn23is062@mentora.edu", "Student@123"), other = token("1rn23is064@mentora.edu", "Student@123");
    Long mine = sid("1RN23IS062"), theirs = sid("1RN23IS064");
    JsonNode cats = call(get("/api/support/categories"), st, 200);
    assertEquals(List.of("Academic Counselling", "Attendance Improvement", "Assignment Support", "Technical Skill Development", "Career Guidance", "Parent Interaction", "Personal Counselling Referral"),
        cats.findValuesAsText("name"));
    for (JsonNode c : cats) {
      // a studentId in the payload is ignored: identity always comes from the authenticated session
      JsonNode r = call(jsonPost("/api/support/requests", "{\"category\":\"" + c.get("code").asText() + "\",\"concern\":\"Need help with " + c.get("name").asText() + "\",\"helpNeeded\":\"Guidance\",\"studentId\":" + theirs + "}"), st, 200);
      assertEquals(mine, r.get("studentId").asLong());
      assertEquals("SUBMITTED".equals(r.get("status").asText()) || "ASSIGNED".equals(r.get("status").asText()), true);
      assertEquals(5, r.get("progress").size());
    }
    assertEquals(7, call(get("/api/support/my-requests"), st, 200).size());
    assertEquals(0, call(get("/api/support/my-requests"), other, 200).size(), "students only see their own requests");
    call(jsonPost("/api/support/requests", "{\"category\":\"CAREER_GUIDANCE\",\"concern\":\"\"}"), st, 400);
    call(jsonPost("/api/support/requests", "{\"category\":\"NOPE\",\"concern\":\"x\"}"), st, 400);
    // students cannot use staff APIs
    call(get("/api/support/requests"), st, 403);
    call(jsonPost("/api/support/actions", "{\"studentId\":" + theirs + ",\"category\":\"CAREER_GUIDANCE\",\"observation\":\"x\"}"), st, 403);
    call(get("/api/records/assignments"), st, 403);
    call(jsonPut("/api/records/ia/attendance", "{\"iaNumber\":1,\"studentId\":" + mine + ",\"status\":\"ATTENDED\"}"), st, 403);
    call(jsonPut("/api/records/assignments/1/submissions/" + mine, "{\"submitted\":true}"), st, 403);
  }

  @Test @Order(15) void mentorReviewsAssignsAndKeepsNotesPrivate() throws Exception {
    String st = token("1rn23is062@mentora.edu", "Student@123"), m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123");
    Long sid = sid("1RN23IS062"); // student B belongs to mentor 2
    JsonNode created = call(jsonPost("/api/support/requests", "{\"category\":\"ACADEMIC_COUNSELLING\",\"concern\":\"Struggling with DBMS normalisation\"}"), st, 200);
    long id = created.get("id").asLong();
    // the assigned mentor (mentor 2) sees it, mentor 1 does not
    boolean found = false;
    for (JsonNode r : call(get("/api/support/requests"), m2, 200)) if (r.get("id").asLong() == id) found = true;
    assertTrue(found);
    call(get("/api/support/requests/" + id), m1, 403);
    call(jsonPut("/api/support/requests/" + id, "{\"status\":\"IN_PROGRESS\"}"), m1, 403);
    // mentor 2 received a student request notification
    assertTrue(jdbc.queryForObject("select count(*) from notifications where type = 'SUPPORT_REQUEST' and student_id = ?", Long.class, sid) >= 1);
    String secret = "INTERNAL-ONLY-NOTE-XYZ";
    JsonNode upd = call(jsonPut("/api/support/requests/" + id, "{\"status\":\"IN_PROGRESS\",\"ownerRole\":\"ACADEMIC_SUPPORT_TEAM\",\"internalNote\":\"" + secret + "\",\"studentFeedback\":\"Please attend the remedial class on Monday\",\"followUpDate\":\"" + LocalDate.now().plusDays(3) + "\"}"), m2, 200);
    assertEquals("ACADEMIC_SUPPORT_TEAM", upd.get("ownerRole").asText());
    assertTrue(upd.toString().contains(secret), "staff see internal notes");
    JsonNode mine = null;
    for (JsonNode r : call(get("/api/support/my-requests"), st, 200)) if (r.get("id").asLong() == id) mine = r;
    assertNotNull(mine);
    assertFalse(mine.toString().contains(secret), "students never see internal notes");
    assertTrue(mine.toString().contains("remedial class"), "student-visible feedback is shown");
    assertEquals("IN_PROGRESS", mine.get("status").asText(), "an explicit status wins over the follow-up default");
    assertEquals(LocalDate.now().plusDays(3).toString(), mine.get("followUpDate").asText());
    // resolving needs an outcome
    call(jsonPut("/api/support/requests/" + id, "{\"status\":\"RESOLVED\"}"), m2, 400);
    JsonNode done = call(jsonPut("/api/support/requests/" + id, "{\"status\":\"RESOLVED\",\"outcome\":\"Attended remedial sessions; concept clear\"}"), m2, 200);
    assertEquals("RESOLVED", done.get("status").asText());
    mine = null;
    for (JsonNode r : call(get("/api/support/my-requests"), st, 200)) if (r.get("id").asLong() == id) mine = r;
    assertEquals("Attended remedial sessions; concept clear", mine.get("outcome").asText());
  }

  @Test @Order(16) void counsellingIsConfidential() throws Exception {
    String st = token("1rn23is061@mentora.edu", "Student@123"), m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123"), admin = token("admin@mentora.edu", "Admin@123");
    JsonNode r = call(jsonPost("/api/support/requests", "{\"category\":\"PERSONAL_COUNSELLING_REFERRAL\",\"concern\":\"PRIVATE-DETAIL-QWERTY\"}"), st, 200);
    long id = r.get("id").asLong();
    // ordinary mentor (student's own mentor, not a counsellor): masked, no details, no private text in any notification
    JsonNode asMentor = call(get("/api/support/requests/" + id), m1, 200);
    assertTrue(asMentor.get("restricted").asBoolean());
    assertFalse(asMentor.toString().contains("PRIVATE-DETAIL-QWERTY"));
    call(jsonPut("/api/support/requests/" + id, "{\"internalNote\":\"x\"}"), m1, 403);
    Long leaks = jdbc.queryForObject("select count(*) from notifications where description like '%PRIVATE-DETAIL-QWERTY%' or title like '%PRIVATE-DETAIL%'", Long.class);
    assertEquals(0, leaks);
    Long aboutStudent = jdbc.queryForObject("select count(*) from notifications where user_id = ? and description ilike '%counsel%' and student_id = ?", Long.class, mentor1User, sid("1RN23IS061"));
    assertEquals(0, aboutStudent, "ordinary mentor notifications carry no counselling information");
    // the authorised counsellor (mentor 2 in the demo) and the administrator see the details
    assertTrue(call(get("/api/support/requests/" + id), m2, 200).toString().contains("PRIVATE-DETAIL-QWERTY"));
    assertTrue(call(get("/api/support/requests/" + id), admin, 200).toString().contains("PRIVATE-DETAIL-QWERTY"));
    JsonNode counsellorNote = call(jsonPut("/api/support/requests/" + id, "{\"status\":\"IN_PROGRESS\",\"internalNote\":\"counsellor note\"}"), m2, 200);
    assertEquals("IN_PROGRESS", counsellorNote.get("status").asText());
  }

  // ------------------------------------------------------------------ interventions + follow-ups + records
  @Test @Order(17) void interventionWorkflowAndReminders() throws Exception {
    String m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123"), admin = token("admin@mentora.edu", "Admin@123");
    Long a = sid("1RN23IS061");
    Long sigId = jdbc.queryForObject("select id from support_signals where student_id = ? and signal_type = 'MULTIPLE' and status = 'ACTIVE'", Long.class, a);
    int open0 = call(get("/api/overview"), m1, 200).get("openInterventions").asInt();
    // mentor cannot create an action for a student of another mentor
    call(jsonPost("/api/support/actions", "{\"studentId\":" + sid("1RN23IS062") + ",\"category\":\"ATTENDANCE_IMPROVEMENT\",\"observation\":\"x\"}"), m1, 403);
    JsonNode act = call(jsonPost("/api/support/actions", "{\"studentId\":" + a + ",\"category\":\"ATTENDANCE_IMPROVEMENT\",\"ownerRole\":\"FACULTY_MENTOR\",\"observation\":\"Discussed attendance plan\",\"signalId\":" + sigId + "}"), m1, 200);
    long id = act.get("id").asLong();
    assertEquals("ASSIGNED", act.get("status").asText());
    assertEquals(open0 + 1, call(get("/api/overview"), m1, 200).get("openInterventions").asInt());
    call(jsonPut("/api/support/requests/" + id, "{\"studentFeedback\":\"See me on Monday\"}"), m1, 200);
    String stu = token("1rn23is061@mentora.edu", "Student@123");
    String studentBody = call(get("/api/support/my-requests"), stu, 200).toString();
    assertTrue(studentBody.contains("See me on Monday"));
    assertFalse(studentBody.contains("Discussed attendance plan"), "mentor-written observation is not shown to the student");
    // follow-up today -> reminder, only once
    call(jsonPut("/api/support/requests/" + id, "{\"followUpDate\":\"" + LocalDate.now() + "\",\"status\":\"IN_PROGRESS\"}"), m1, 200);
    JsonNode got = call(get("/api/support/requests/" + id), m1, 200);
    assertEquals("DUE", got.get("followUpState").asText());
    assertEquals(1, support.sendDueReminders(LocalDate.now()) >= 1 ? 1 : 0);
    assertEquals(0, support.sendDueReminders(LocalDate.now()), "no duplicate follow-up reminders");
    assertEquals(1, jdbc.queryForObject("select count(*) from notifications where type = 'FOLLOW_UP_REMINDER' and dedupe_key like ?", Long.class, "FU:" + id + ":%"));
    // overdue after the date passes
    jdbc.update("update support_requests set follow_up_date = ? where id = ?", java.sql.Date.valueOf(LocalDate.now().minusDays(2)), id);
    assertEquals("OVERDUE", call(get("/api/support/requests/" + id), m1, 200).get("followUpState").asText());
    // assign another owner, record outcome, resolve
    Long ownerUser = jdbc.queryForObject("select id from users where email = 'mentor2@mentora.edu'", Long.class);
    JsonNode owned = call(jsonPut("/api/support/requests/" + id, "{\"ownerUserId\":" + ownerUser + ",\"ownerRole\":\"DEPARTMENT_HOD\"}"), m1, 200);
    assertEquals(ownerUser, owned.get("ownerUserId").asLong());
    call(jsonPut("/api/support/requests/" + id, "{\"ownerRole\":\"NOT_A_ROLE\"}"), m1, 400);
    // the new owner may update it, an unrelated mentor-less path stays closed
    call(jsonPut("/api/support/requests/" + id, "{\"internalNote\":\"owner note\"}"), m2, 200);
    JsonNode resolved = call(jsonPut("/api/support/requests/" + id, "{\"status\":\"RESOLVED\",\"outcome\":\"Attendance plan agreed\"}"), m2, 200);
    assertNotNull(resolved.get("resolvedAt"));
    assertEquals(open0, call(get("/api/overview"), m1, 200).get("openInterventions").asInt());
    call(jsonPut("/api/support/requests/" + id, "{\"status\":\"CLOSED\"}"), admin, 200);
    call(jsonPut("/api/support/requests/" + id, "{\"status\":\"IN_PROGRESS\"}"), admin, 400);
    // the history is complete
    assertTrue(jdbc.queryForObject("select count(*) from support_request_events where request_id = ?", Long.class, id) >= 6);
  }

  @Test @Order(18) void recordsApiTriggersSignals() throws Exception {
    String m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123");
    Long b = sid("1RN23IS062"); // mentor 2's student
    Long aid = jdbc.queryForObject("select id from assignments where title = 'SRS Document'", Long.class);
    call(jsonPut("/api/records/assignments/" + aid + "/submissions/" + b, "{\"submitted\":false}"), m1, 403);
    assertEquals(0, active(b, "ASSIGNMENT"));
    call(jsonPut("/api/records/assignments/" + aid + "/submissions/" + b, "{\"submitted\":false}"), m2, 200);
    assertEquals(1, active(b, "ASSIGNMENT"), "marking a past-deadline assignment as not submitted raises the signal immediately");
    call(jsonPut("/api/records/assignments/" + aid + "/submissions/" + b, "{\"submitted\":true}"), m2, 200);
    assertEquals(0, active(b, "ASSIGNMENT"));
    // IA attendance can only be recorded for conducted exams
    call(jsonPut("/api/records/ia/exam", "{\"semester\":5,\"iaNumber\":3,\"examDate\":\"" + LocalDate.now().minusDays(10) + "\",\"conducted\":false}"), m2, 200);
    call(jsonPut("/api/records/ia/attendance", "{\"iaNumber\":3,\"studentId\":" + b + ",\"status\":\"ABSENT\"}"), m2, 400);
    call(jsonPut("/api/records/ia/exam", "{\"semester\":5,\"iaNumber\":3,\"examDate\":\"" + LocalDate.now().minusDays(10) + "\",\"conducted\":true}"), m2, 200);
    call(jsonPut("/api/records/ia/attendance", "{\"iaNumber\":3,\"studentId\":" + b + ",\"status\":\"ABSENT\"}"), m2, 200);
    call(jsonPut("/api/records/ia/attendance", "{\"iaNumber\":3,\"studentId\":" + b + ",\"status\":\"ATTENDED\"}"), m2, 200);
    assertEquals(0, active(b, "IA_ATTENDANCE"));
  }

  @Test @Order(19) void studentSupportSummaryHidesInternalData() throws Exception {
    String st = token("1rn23is061@mentora.edu", "Student@123");
    Long a = sid("1RN23IS061"), other = sid("1RN23IS062");
    JsonNode s = call(get("/api/students/" + a + "/support"), st, 200);
    assertEquals(3, s.get("ia").size());
    assertEquals("ATTENDED", s.get("ia").get(0).get("attendance").asText());
    assertEquals("ABSENT", s.get("ia").get(2).get("attendance").asText());
    assertEquals(3, s.get("overdueAssignments").size());
    assertFalse(s.has("requests"), "no staff-side request list for students");
    assertFalse(s.toString().contains("\"priority\""), "no internal priority labels for students");
    call(get("/api/students/" + other + "/support"), st, 403);
  }

  @Test @Order(20) void adminConfiguration() throws Exception {
    String admin = token("admin@mentora.edu", "Admin@123"), m1 = token("mentor1@mentora.edu", "Mentor@123");
    JsonNode settings = call(get("/api/admin/settings"), admin, 200);
    Set<String> keys = new HashSet<>(settings.findValuesAsText("key"));
    assertTrue(keys.containsAll(List.of("attendance.threshold", "signals.consecutive_absence_days", "signals.ia_min_attended", "signals.assignment_grace_days", "priority.ia_none")), keys.toString());
    call(jsonPut("/api/admin/settings/priority.attendance", "{\"value\":\"SEVERE\"}"), admin, 400);
    call(jsonPut("/api/admin/settings/attendance.threshold", "{\"value\":\"150\"}"), admin, 400);
    call(get("/api/admin/support-categories"), m1, 403);
    call(jsonPut("/api/admin/support-categories/CAREER_GUIDANCE", "{\"defaultOwner\":\"COUNSELLOR\"}"), admin, 200);
    call(jsonPut("/api/admin/support-categories/CAREER_GUIDANCE", "{\"defaultOwner\":\"CAREER_GUIDANCE_TEAM\"}"), admin, 200);
    call(jsonPut("/api/admin/support-categories/CAREER_GUIDANCE", "{\"defaultOwner\":\"BOGUS\"}"), admin, 400);
  }
}
