package com.mentora.service;

import com.mentora.config.AppProps;
import com.mentora.domain.*;
import com.mentora.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * Demo scenarios for the early-warning system (skipped when SEED_DEMO_DATA=false). Additive and idempotent:
 * adds five clearly demo students (USN 1RN23IS061-065), the semester-5 assignments and IA schedule, and submissions / IA attendance
 * for the existing demo students. Never touches existing attendance or marks.
 */
@Service
@Order(20)
public class SupportDemoSeeder implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(SupportDemoSeeder.class);
  /** missed = bit mask over assignments (index 0 = oldest) the student did not submit; ia = IA1..IA3 attendance (A attended, X absent). */
  record Scenario(String usn, String name, int absences, int trailing, int missed, String ia, boolean mentor1, String note) {}
  static final List<Scenario> SCENARIOS = List.of(
      new Scenario("1RN23IS061", "Rhea Menon", 32, 3, 0b01110, "AXX", true, "A: 68% attendance, 3 overdue assignments, IA 1/3, 3 consecutive absences"),
      new Scenario("1RN23IS062", "Aditya Verma", 15, 0, 0, "AAA", false, "B: 85% attendance, nothing overdue, IA 3/3"),
      new Scenario("1RN23IS063", "Ishaan Mehta", 20, 0, 0, "XXX", true, "C: 80% attendance, IA 0/3"),
      new Scenario("1RN23IS064", "Sanjana Rao", 28, 0, 0, "AAA", false, "D: 72% attendance only"),
      new Scenario("1RN23IS065", "Karthik Naidu", 10, 0, 0b00100, "AAX", true, "E: 90% attendance, one overdue assignment, IA 2/3"));

  private final AppProps props;
  private final JdbcTemplate jdbc;
  private final UserRepository users;
  private final StudentRepository students;
  private final FacultyRepository faculty;
  private final MentorAssignmentRepository assignments;
  private final SubjectRepository subjects;
  private final AttendanceRepository attendance;
  private final SupportSignalService signals;
  private final PasswordEncoder encoder;
  private final TransactionTemplate tx;

  public SupportDemoSeeder(AppProps props, JdbcTemplate jdbc, UserRepository users, StudentRepository students, FacultyRepository faculty, MentorAssignmentRepository assignments,
                           SubjectRepository subjects, AttendanceRepository attendance, SupportSignalService signals, PasswordEncoder encoder, TransactionTemplate tx) {
    this.props = props; this.jdbc = jdbc; this.users = users; this.students = students; this.faculty = faculty; this.assignments = assignments; this.subjects = subjects;
    this.attendance = attendance; this.signals = signals; this.encoder = encoder; this.tx = tx;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.seedDemoData()) return;
    try { tx.executeWithoutResult(s -> seed()); } catch (Exception e) { log.warn("Support demo seeding skipped because of an error: {}", e.toString()); }
  }

  private void seed() {
    Faculty m1 = faculty.findAll().stream().filter(f -> "EMP1001".equals(f.getEmployeeId())).findFirst().orElse(null);
    Faculty m2 = faculty.findAll().stream().filter(f -> "EMP1002".equals(f.getEmployeeId())).findFirst().orElse(null);
    List<Subject> sem5 = subjects.findBySemesterOrderByCode(5);
    if (m1 == null || m2 == null || sem5.size() < 5) { log.info("Demo mentors/subjects not present; support demo data skipped"); return; }
    jdbc.update("update faculty set is_counsellor = true where id = ?", m2.getId()); // demo: the second mentor is the authorised counsellor

    LocalDate today = LocalDate.now();
    if (jdbc.queryForObject("select count(*) from assignments", Integer.class) == 0) {
      String[][] a = {{"ER Diagram Design", "-40", "0"}, {"CPU Scheduling", "-21", "2"}, {"Subnetting Worksheet", "-10", "1"}, {"SRS Document", "-3", "3"}, {"Regression Mini Project", "14", "4"}};
      for (String[] x : a) jdbc.update("insert into assignments(subject_id, title, deadline) values (?,?,?)", sem5.get(Integer.parseInt(x[2])).getId(), x[0], java.sql.Date.valueOf(today.plusDays(Long.parseLong(x[1]))));
      int[] off = {-60, -35, -10};
      for (int i = 1; i <= 3; i++) jdbc.update("insert into ia_exams(semester, ia_number, exam_date, conducted) values (5,?,?,true) on conflict do nothing", i, java.sql.Date.valueOf(today.plusDays(off[i - 1])));
      jdbc.update("insert into holidays(holiday_date, name) values (?, ?) on conflict do nothing", java.sql.Date.valueOf(today.minusDays(28)), "Demo college holiday");
      seedExistingStudents();
    }

    int added = 0;
    for (Scenario sc : SCENARIOS) {
      if (students.findByUsnIgnoreCase(sc.usn()).isPresent() || users.existsByEmailIgnoreCase(sc.usn().toLowerCase() + "@mentora.edu")) continue;
      User u = new User(); u.setFullName(sc.name()); u.setEmail(sc.usn().toLowerCase() + "@mentora.edu"); u.setPasswordHash(encoder.encode("Student@123")); u.setRole(Role.STUDENT); users.save(u);
      Student st = new Student(); st.setUserId(u.getId()); st.setDepartmentId(m1.getDepartmentId()); st.setUsn(sc.usn()); st.setSemester(5); st.setSection("A"); students.save(st);
      MentorAssignment ma = new MentorAssignment(); ma.setStudentId(st.getId()); ma.setFacultyId((sc.mentor1() ? m1 : m2).getId()); assignments.save(ma);
      added++;
      List<LocalDate> days = new ArrayList<>();
      for (LocalDate d = today; days.size() < 100; d = d.minusDays(1)) if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) days.add(0, d);
      Set<LocalDate> absent = new HashSet<>();
      for (int i = 0; i < sc.trailing(); i++) absent.add(days.get(days.size() - 1 - i));
      List<LocalDate> pool = new ArrayList<>(days.subList(0, days.size() - Math.max(3, sc.trailing() + 1)));
      Collections.shuffle(pool, new Random(sc.usn().hashCode()));
      for (int i = 0; absent.size() < sc.absences() && i < pool.size(); i++) absent.add(pool.get(i));
      for (LocalDate d : days) for (Subject sub : sem5) {
        Attendance at = new Attendance(); at.setStudentId(st.getId()); at.setSubjectId(sub.getId()); at.setDate(d); at.setStatus(absent.contains(d) ? AttStatus.A : AttStatus.P); attendance.save(at);
      }
      var aIds = jdbc.queryForList("select id from assignments order by deadline", Long.class);
      for (int i = 0; i < aIds.size(); i++) {
        boolean past = i < 4;
        if (past && (sc.missed() & (1 << i)) == 0) jdbc.update("insert into assignment_submissions(assignment_id, student_id) values (?,?) on conflict do nothing", aIds.get(i), st.getId());
      }
      var exams = jdbc.queryForList("select id from ia_exams where semester = 5 order by ia_number", Long.class);
      for (int i = 0; i < 3; i++) jdbc.update("insert into ia_exam_attendance(exam_id, student_id, status) values (?,?,?) on conflict do nothing", exams.get(i), st.getId(), sc.ia().charAt(i) == 'A' ? "ATTENDED" : "ABSENT");
    }
    attendance.flush();
    int n = signals.evaluateAll(today);
    log.info("Support demo data ready ({} new scenario students, {} notifications)", added, n);
  }

  /** Existing demo students: they submitted all past assignments (a few gaps) and attended all IA examinations (a few exceptions). */
  private void seedExistingStudents() {
    var aIds = jdbc.queryForList("select id from assignments order by deadline", Long.class);
    var exams = jdbc.queryForList("select id from ia_exams where semester = 5 order by ia_number", Long.class);
    for (int i = 0; i < 60; i++) {
      Optional<Student> st = students.findByUsnIgnoreCase(String.format("1RN23IS%03d", i + 1));
      if (st.isEmpty()) continue;
      Long sid = st.get().getId();
      for (int k = 0; k < 4; k++) {
        boolean gap = (i == 5 && k == 3) || (i == 20 && k == 1) || (i == 33 && k == 2);
        if (!gap) jdbc.update("insert into assignment_submissions(assignment_id, student_id) values (?,?) on conflict do nothing", aIds.get(k), sid);
      }
      for (int e = 0; e < 3; e++) {
        boolean absent = (i == 12 && e >= 1) || (i == 3 && e == 2);
        jdbc.update("insert into ia_exam_attendance(exam_id, student_id, status) values (?,?,?) on conflict do nothing", exams.get(e), sid, absent ? "ABSENT" : "ATTENDED");
      }
    }
  }
}
