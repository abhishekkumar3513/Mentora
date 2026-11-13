package com.mentora.service;

import com.mentora.config.AppProps;
import com.mentora.domain.*;
import com.mentora.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * Deterministic, idempotent demo data (skipped when SEED_DEMO_DATA=false). Never enable in production.
 * Creates 60 students (30 per mentor). On an existing database it only tops up the missing students
 * (by USN) and never deletes or rewrites existing records. Attendance is generated relative to the current date.
 *
 * Mentor 1 (Dr. Sunitha K):        1RN23IS001-010 and 1RN23IS021-040
 * Mentor 2 (Ms. Nivedita G. Y.):   1RN23IS011-020 and 1RN23IS041-060
 */
@Service
@Order(10)
public class DemoDataSeeder implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
  public static final String ADMIN_EMAIL = "admin@mentora.edu";
  public static final String MENTOR1_NAME = "Dr. Sunitha K";
  public static final String MENTOR2_NAME = "Ms. Nivedita G. Y.";
  static final int STUDENT_COUNT = 60;

  /** Student scenario profile: IA percentages, attendance behaviour and notes. */
  record Profile(String name, double ia1, double ia2, double ia3, double absentProb, int trailingAbsent, String scenario) {}

  private final AppProps props;
  private final UserRepository users;
  private final DepartmentRepository departments;
  private final SemesterRepository semesters;
  private final FacultyRepository faculty;
  private final StudentRepository students;
  private final MentorAssignmentRepository assignments;
  private final SubjectRepository subjects;
  private final AttendanceRepository attendance;
  private final MarkRepository marks;
  private final SemesterResultRepository results;
  private final LeaveRequestRepository leaves;
  private final LeaveDocumentRepository leaveDocs;
  private final LeaveAuditRepository leaveAudit;
  private final MessageRepository messages;
  private final MentorFeedbackRepository feedback;
  private final AcademicObservationRepository observations;
  private final NotificationService notifications;
  private final AttendanceAlertService alerts;
  private final AnalyticsService analytics;
  private final PasswordEncoder encoder;
  private final TransactionTemplate tx;

  public DemoDataSeeder(AppProps props, UserRepository users, DepartmentRepository departments, SemesterRepository semesters, FacultyRepository faculty,
                        StudentRepository students, MentorAssignmentRepository assignments, SubjectRepository subjects, AttendanceRepository attendance,
                        MarkRepository marks, SemesterResultRepository results, LeaveRequestRepository leaves, LeaveDocumentRepository leaveDocs,
                        LeaveAuditRepository leaveAudit, MessageRepository messages, MentorFeedbackRepository feedback, AcademicObservationRepository observations,
                        NotificationService notifications, AttendanceAlertService alerts, AnalyticsService analytics, PasswordEncoder encoder, TransactionTemplate tx) {
    this.props = props; this.users = users; this.departments = departments; this.semesters = semesters; this.faculty = faculty; this.students = students;
    this.assignments = assignments; this.subjects = subjects; this.attendance = attendance; this.marks = marks; this.results = results; this.leaves = leaves;
    this.leaveDocs = leaveDocs; this.leaveAudit = leaveAudit; this.messages = messages; this.feedback = feedback; this.observations = observations;
    this.notifications = notifications; this.alerts = alerts; this.analytics = analytics; this.encoder = encoder; this.tx = tx;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.seedDemoData()) { log.info("Demo seeding disabled"); return; }
    try {
      tx.executeWithoutResult(s -> seed());
    } catch (Exception e) {
      // Demo data is optional: a seeding problem (for example accounts the admin has since renamed) must never stop the application.
      log.warn("Demo data seeding skipped because of an error: {}", e.toString());
    }
  }

  // Templates 0-19 (first 20 students). Students 21-60 reuse these scenarios with different people.
  static final List<Profile> PROFILES = List.of(
      new Profile("A Srivatsa", 90, 92, 91, 0.03, 0, "High-performing student"),
      new Profile("Aanchal Upreti", 55, 68, 82, 0.06, 0, "Marks improving"),
      new Profile("Aashmit Abhijit S", 86, 72, 56, 0.12, 1, "Marks declining; absent today"),
      new Profile("Abhinav Bhardawaj", 62, 60, 64, 0.38, 0, "Low attendance"),
      new Profile("Abhiram A Bhat", 70, 68, 71, 0.10, 3, "Consecutive absences"),
      new Profile("Achappa Varsha", 74, 72, 75, 0.05, 2, "Pending medical leave application"),
      new Profile("Adarsh B H", 66, 64, 67, 0.01, 0, "Excellent attendance"),
      new Profile("Bhavya Nair", 42, 40, 38, 0.04, 0, "Good attendance, weak internal marks"),
      new Profile("Chaitra Gowda", 88, 91, 93, 0.04, 0, "Consistently strong internals"),
      new Profile("Darshan Rao", 68, 70, 69, 0.08, 0, "Approved leave application"),
      new Profile("Divya Prakash", 92, 94, 95, 0.02, 0, "High-performing student"),
      new Profile("Eshwar Murthy", 84, 70, 58, 0.09, 0, "Marks declining"),
      new Profile("Farhan Sheikh", 60, 58, 61, 0.35, 0, "Low attendance"),
      new Profile("Gayathri Hegde", 50, 64, 79, 0.05, 0, "Marks improving"),
      new Profile("Harsha Vardhan", 64, 63, 66, 0.01, 0, "Excellent attendance"),
      new Profile("Isha Kulkarni", 41, 44, 39, 0.05, 0, "Good attendance, weak internal marks"),
      new Profile("Jayanth Reddy", 72, 70, 73, 0.10, 2, "Consecutive absences"),
      new Profile("Kavya Shenoy", 71, 69, 72, 0.06, 1, "Pending personal leave application"),
      new Profile("Lohith Gowda", 87, 89, 90, 0.04, 0, "Consistently strong internals"),
      new Profile("Meghana Joshi", 67, 66, 68, 0.07, 0, "Approved leave application"));

  private static final String[] EXTRA_NAMES = {
      "Nikhil Bhat", "Ojasvi Rao", "Pranav Shetty", "Zoya Qureshi", "Rohan Desai", "Sneha Patil", "Tanvi Kamath", "Uday Hegde", "Vaishnavi Iyer", "Yash Chandra",
      "Ananya Bhandari", "Bharath Kumar", "Charitha Reddy", "Dhruv Menon", "Esha Naik", "Farida Begum", "Girish Acharya", "Hemanth Gowda", "Indu Prabhu", "Jagadish Pai",
      "Kiran Shastri", "Lavanya Rao", "Manoj Nayak", "Nandini Hebbar", "Omkar Joshi", "Prathima Bhat", "Rakshith Shetty", "Sahana Murthy", "Tejas Kulkarni", "Usha Rani",
      "Varun Gowda", "Yamini Krishna", "Zubair Ahmed", "Akash Deshpande", "Bindu Madhavi", "Chirag Jain", "Deepika Sridhar", "Eshaan Verma", "Fathima Noor", "Gagan Raj"};

  private static final String[][] SEM5 = {{"23IS501", "Database Management Systems", "4"}, {"23IS502", "Computer Networks", "4"},
      {"23IS503", "Operating Systems", "4"}, {"23IS504", "Software Engineering", "3"}, {"23IS505", "Machine Learning", "3"}};
  private static final String[][][] PAST = {
      {{"23IS101", "Engineering Mathematics I", "4"}, {"23IS102", "Engineering Physics", "4"}, {"23IS103", "Programming in C", "3"}, {"23IS104", "Basic Electronics", "3"}},
      {{"23IS201", "Engineering Mathematics II", "4"}, {"23IS202", "Engineering Chemistry", "4"}, {"23IS203", "Data Structures", "4"}, {"23IS204", "Digital Design", "3"}},
      {{"23IS301", "Discrete Mathematics", "3"}, {"23IS302", "Object Oriented Programming with Java", "4"}, {"23IS303", "Computer Organization", "4"}, {"23IS304", "Analog and Digital Electronics", "3"}},
      {{"23IS401", "Design and Analysis of Algorithms", "4"}, {"23IS402", "Microcontrollers", "4"}, {"23IS403", "Theory of Computation", "3"}, {"23IS404", "Data Communication", "3"}}};

  /** Index 0-based. Mentor 1: 0-9 and 20-39. Mentor 2: 10-19 and 40-59. */
  static boolean mentor1Owns(int i) { return i < 10 || (i >= 20 && i < 40); }

  static Profile profileFor(int i) {
    Profile t = PROFILES.get(i % PROFILES.size());
    return i < PROFILES.size() ? t : new Profile(EXTRA_NAMES[i - PROFILES.size()], t.ia1(), t.ia2(), t.ia3(), t.absentProb(), t.trailingAbsent(), t.scenario());
  }

  private void seed() {
    // "Fresh" = an empty database. On an existing one nothing is looked up by demo email (admins may have changed them);
    // mentors are found by their employee IDs and only the missing demo students are added.
    boolean fresh = users.count() == 0;
    if (!fresh && java.util.stream.IntStream.range(0, STUDENT_COUNT).allMatch(i -> students.findByUsnIgnoreCase(String.format("1RN23IS%03d", i + 1)).isPresent())) {
      log.info("Demo data already complete; nothing to seed");
      return;
    }
    Department ise;
    User m1u, m2u;
    Faculty m1, m2;
    List<Subject> sem5 = new ArrayList<>();
    List<List<Subject>> past = new ArrayList<>();
    if (fresh) {
      ise = new Department(); ise.setCode("ISE"); ise.setName("Information Science and Engineering"); departments.save(ise);
      Department cse = new Department(); cse.setCode("CSE"); cse.setName("Computer Science and Engineering"); departments.save(cse);
      for (int i = 1; i <= 6; i++) {
        Semester s = new Semester(); s.setNumber(i); s.setAcademicYear(i <= 4 ? "2023-24" : "2025-26"); s.setCurrent(i == 5); semesters.save(s);
      }
      user("Administrator", ADMIN_EMAIL, "Admin@123", Role.ADMIN);
      m1u = user(MENTOR1_NAME, "mentor1@mentora.edu", "Mentor@123", Role.MENTOR);
      m2u = user(MENTOR2_NAME, "mentor2@mentora.edu", "Mentor@123", Role.MENTOR);
      m1 = fac(m1u, ise, "EMP1001", "Associate Professor");
      m2 = fac(m2u, ise, "EMP1002", "Assistant Professor");
      for (String[] s : SEM5) sem5.add(subject(s, 5, ise));
      for (int i = 0; i < PAST.length; i++) { List<Subject> l = new ArrayList<>(); for (String[] s : PAST[i]) l.add(subject(s, i + 1, ise)); past.add(l); }
    } else {
      Optional<Department> dept = departments.findByCodeIgnoreCase("ISE");
      m1 = faculty.findAll().stream().filter(f -> "EMP1001".equals(f.getEmployeeId())).findFirst().orElse(null);
      m2 = faculty.findAll().stream().filter(f -> "EMP1002".equals(f.getEmployeeId())).findFirst().orElse(null);
      if (dept.isEmpty() || m1 == null || m2 == null) {
        log.warn("Demo mentors (EMP1001/EMP1002) or the ISE department were removed; not adding more demo students");
        return;
      }
      ise = dept.get();
      m1u = users.findById(m1.getUserId()).orElseThrow();
      m2u = users.findById(m2.getUserId()).orElseThrow();
      sem5.addAll(subjects.findBySemesterOrderByCode(5));
      for (int sem = 1; sem <= 4; sem++) past.add(subjects.findBySemesterOrderByCode(sem));
    }

    LocalDate today = LocalDate.now();
    List<LocalDate> days = new ArrayList<>();
    for (LocalDate d = today.minusDays(44); !d.isAfter(today); d = d.plusDays(1)) if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) days.add(d);

    Map<Integer, Student> created = new LinkedHashMap<>();
    for (int i = 0; i < STUDENT_COUNT; i++) {
      String usn = String.format("1RN23IS%03d", i + 1);
      if (students.findByUsnIgnoreCase(usn).isPresent()) continue;
      if (users.existsByEmailIgnoreCase(usn.toLowerCase() + "@mentora.edu")) continue;   // email taken by a renamed account
      Profile p = profileFor(i);
      User u = user(p.name(), usn.toLowerCase() + "@mentora.edu", "Student@123", Role.STUDENT);
      Student s = new Student(); s.setUserId(u.getId()); s.setDepartmentId(ise.getId()); s.setUsn(usn); s.setSemester(5); s.setSection("A"); students.save(s);
      MentorAssignment a = new MentorAssignment(); a.setStudentId(s.getId()); a.setFacultyId(mentor1Owns(i) ? m1.getId() : m2.getId()); assignments.save(a);
      created.put(i, s);
      generateRecords(s, p, new Random(20260101L + i * 7919L), sem5, past, days);
    }
    if (created.isEmpty()) { log.info("Demo data already complete; nothing to seed"); return; }
    attendance.flush();
    marks.flush();

    // ---- leave applications and conversations, tied to scenario templates ----
    for (var e : created.entrySet()) {
      int i = e.getKey(); Student s = e.getValue();
      User mentorUser = mentor1Owns(i) ? m1u : m2u;
      switch (i % PROFILES.size()) {
        case 5 -> {
          LeaveRequest l = leave(s, LeaveType.MEDICAL, days.get(days.size() - 2), days.get(days.size() - 1),
              "I was suffering from a high fever and the doctor advised me to take rest. I have attached the medical certificate. Kindly consider my absence.", LeaveStatus.PENDING, null, null);
          attachSamplePdf(l, "medical_certificate.pdf");
          if (i == 5) {
            msg(s, s.getUserId(), "Sir, I was not able to attend college because I was having a high fever and the doctor advised me to take rest. I have attached the medical certificate. Kindly consider my absence.", 20);
            msg(s, mentorUser.getId(), "Get well soon. I will review your leave application today.", 12);
          }
        }
        case 17 -> leave(s, LeaveType.PERSONAL, days.get(days.size() - 1), days.get(days.size() - 1), "Family function out of town. I request leave for the day.", LeaveStatus.PENDING, null, null);
        case 9 -> {
          LeaveRequest l = leave(s, LeaveType.COLLEGE_EVENT, days.get(days.size() - 9), days.get(days.size() - 8), "Participating in the inter-college hackathon.", LeaveStatus.APPROVED, mentorUser, "Approved. Share the participation certificate afterwards.");
          attachSamplePdf(l, "event_permission.pdf");
          notifications.notify(s.getUserId(), NotificationType.LEAVE_APPROVED, "Leave application approved", "Your leave was approved: Approved. Share the participation certificate afterwards.", "/student/leaves", "LEAVE_REVIEW:" + l.getId());
        }
        case 19 -> leave(s, LeaveType.MEDICAL, days.get(days.size() - 9), days.get(days.size() - 8), "Viral infection, doctor's rest advised.", LeaveStatus.APPROVED, mentorUser, "Approved. Take care.");
        case 2 -> feedback(s, mentor1Owns(i) ? m1 : m2, "Your marks have dropped in the last two internals. Please revise DBMS normalization and attend the extra class on Saturday.");
        case 3 -> feedback(s, mentor1Owns(i) ? m1 : m2, "Your attendance is below 75%. Please meet me this week to discuss the reasons and plan recovery.");
        case 1 -> feedback(s, mentor1Owns(i) ? m1 : m2, "Excellent improvement from IA 1 to IA 3. Keep up the consistent practice.");
        case 12 -> feedback(s, mentor1Owns(i) ? m1 : m2, "Attendance is a concern. Kindly attend classes regularly to avoid a shortage.");
        case 11 -> observation(s, mentor1Owns(i) ? m1 : m2, "Marks decline correlates with part-time internship workload. Discuss time management.");
        case 7 -> observation(s, mentor1Owns(i) ? m1 : m2, "Attends regularly but struggles with exam technique. Suggest peer tutoring.");
        default -> { }
      }
      if (i == 1) { msg(s, s.getUserId(), "Sir, thank you for the feedback. I focussed on problem solving as you advised.", 300); }
      if (i == 2) {
        msg(s, s.getUserId(), "Good morning sir, could we discuss my IA performance in Database Management Systems?", 90);
        msg(s, m1u.getId(), "Sure, drop by my cabin after the 3 PM class tomorrow and we will go through your answer sheets.", 75);
      }
      if (i == 17) msg(s, s.getUserId(), "Sir, I have applied for a day of personal leave due to a family function.", 40);
    }

    // ---- alerts generated by the real backend logic ----
    LocalDate ref = days.get(days.size() - 1);
    List<Long> ids = created.values().stream().map(Student::getId).toList();
    alerts.evaluate(ids, ref);
    alerts.evaluateAcademic(analytics.metrics(ids));
    for (Student st : created.values()) {
      notifications.notify(st.getUserId(), NotificationType.ANNOUNCEMENT, "Welcome to Mentora", "Track attendance, internal marks and chat with your mentor from one place.", "/student/dashboard", "WELCOME");
    }
    log.info("Demo data seeded: {} new student(s) ({} total demo students)", created.size(), STUDENT_COUNT);
  }

  /** Attendance, internal marks and past semester results for one student. */
  private void generateRecords(Student s, Profile p, Random rnd, List<Subject> sem5, List<List<Subject>> past, List<LocalDate> days) {
    LocalDate ncDay = days.get(5);
    List<LocalDate> lastDays = days.subList(Math.max(0, days.size() - 8), days.size());
    Set<LocalDate> forcedAbsent = new HashSet<>();
    for (int k = 0; k < p.trailingAbsent(); k++) forcedAbsent.add(days.get(days.size() - 1 - k));
    Set<LocalDate> approvedLeaveDays = new HashSet<>();
    if (p.scenario().startsWith("Approved leave")) { approvedLeaveDays.add(days.get(days.size() - 9)); approvedLeaveDays.add(days.get(days.size() - 8)); }
    List<Attendance> att = new ArrayList<>();
    for (LocalDate d : days) {
      AttStatus st;
      if (d.equals(ncDay)) st = AttStatus.NC;
      else if (forcedAbsent.contains(d)) st = AttStatus.A;
      else if (approvedLeaveDays.contains(d)) st = AttStatus.L;
      else if (lastDays.contains(d) && p.trailingAbsent() > 0) st = AttStatus.P;
      else st = rnd.nextDouble() < p.absentProb() ? AttStatus.A : (rnd.nextDouble() < 0.03 ? AttStatus.OD : AttStatus.P);
      for (Subject sub : sem5) {
        Attendance r = new Attendance(); r.setStudentId(s.getId()); r.setSubjectId(sub.getId()); r.setDate(d); r.setStatus(st); att.add(r);
      }
    }
    attendance.saveAll(att);

    double[] ia = {p.ia1(), p.ia2(), p.ia3()};
    Component[] comps = {Component.IA1, Component.IA2, Component.IA3};
    for (Subject sub : sem5) {
      double subjectBias = (rnd.nextDouble() - 0.5) * 10;
      for (int k = 0; k < 3; k++) {
        double pct = clamp(ia[k] + subjectBias + (rnd.nextDouble() - 0.5) * 6, 5, 100);
        saveMark(s, sub, comps[k], BigDecimal.valueOf(Math.round(pct / 100 * 25)));
      }
      for (Component c : new Component[]{Component.ACT1, Component.ACT2, Component.ACT3})
        saveMark(s, sub, c, BigDecimal.valueOf(Math.round(clamp(p.ia3() + (rnd.nextDouble() - 0.5) * 16, 20, 100) / 10)));
    }

    double avg = (p.ia1() + p.ia2() + p.ia3()) / 3;
    for (int sem = 0; sem < past.size(); sem++) {
      for (Subject sub : past.get(sem)) {
        double score = clamp(avg + (rnd.nextDouble() - 0.5) * 18, 30, 99);
        String grade = score >= 90 ? "O" : score >= 80 ? "A+" : score >= 70 ? "A" : score >= 60 ? "B+" : score >= 55 ? "B" : score >= 48 ? "C" : score >= 40 ? "P" : "F";
        SemesterResult r = new SemesterResult();
        r.setStudentId(s.getId()); r.setSubjectId(sub.getId()); r.setSemester(sem + 1); r.setGrade(grade);
        r.setGradePoints(ResultsService.GRADE_POINTS.get(grade)); r.setStatus(grade.equals("F") ? "FAIL" : "PASS");
        results.save(r);
      }
    }
  }

  private User user(String name, String email, String pw, Role role) {
    User u = new User(); u.setFullName(name); u.setEmail(email); u.setPasswordHash(encoder.encode(pw)); u.setRole(role); return users.save(u);
  }

  private Faculty fac(User u, Department d, String emp, String desg) {
    Faculty f = new Faculty(); f.setUserId(u.getId()); f.setDepartmentId(d.getId()); f.setEmployeeId(emp); f.setDesignation(desg); return faculty.save(f);
  }

  private Subject subject(String[] def, int sem, Department d) {
    Subject s = new Subject(); s.setCode(def[0]); s.setName(def[1]); s.setCredits(Integer.parseInt(def[2])); s.setSemester(sem); s.setDepartmentId(d.getId()); return subjects.save(s);
  }

  private void saveMark(Student s, Subject sub, Component c, BigDecimal score) {
    Mark m = new Mark(); m.setStudentId(s.getId()); m.setSubjectId(sub.getId()); m.setComponent(c); m.setScore(score); marks.save(m);
  }

  private LeaveRequest leave(Student s, LeaveType t, LocalDate from, LocalDate to, String reason, LeaveStatus st, User reviewer, String comment) {
    LeaveRequest l = new LeaveRequest();
    l.setStudentId(s.getId()); l.setType(t); l.setStartDate(from); l.setEndDate(to); l.setReason(reason); l.setStatus(st);
    if (reviewer != null) { l.setReviewedBy(reviewer.getId()); l.setReviewComment(comment); l.setReviewedAt(java.time.Instant.now().minusSeconds(3600)); }
    leaves.save(l);
    LeaveAudit a = new LeaveAudit(); a.setLeaveId(l.getId()); a.setAction("SUBMITTED"); a.setActorId(s.getUserId()); leaveAudit.save(a);
    if (reviewer != null) { LeaveAudit r = new LeaveAudit(); r.setLeaveId(l.getId()); r.setAction(st.name()); r.setActorId(reviewer.getId()); r.setComment(comment); leaveAudit.save(r); }
    if (st == LeaveStatus.PENDING) {
      MentorAssignment as = assignments.findByStudentIdAndActiveTrue(s.getId()).orElseThrow();
      Faculty f = faculty.findById(as.getFacultyId()).orElseThrow();
      User su = users.findById(s.getUserId()).orElseThrow();
      notifications.notify(f.getUserId(), NotificationType.NEW_LEAVE, "New leave application", su.getFullName() + " (" + s.getUsn() + ") applied for " + t.name().toLowerCase().replace('_', ' ') + " leave from " + from + " to " + to + ".", "/mentor/leaves", "LEAVE_NEW:" + l.getId());
    }
    return l;
  }

  private void attachSamplePdf(LeaveRequest l, String name) {
    try {
      String rel = "leaves/" + l.getId() + "/seed-" + name;
      Path p = Path.of(props.storageDir()).toAbsolutePath().normalize().resolve(rel);
      Files.createDirectories(p.getParent());
      byte[] pdf = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
          + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 360 200]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n"
          + "4 0 obj<</Length 78>>stream\nBT /F1 16 Tf 24 110 Td (Sample supporting document - Mentora demo) Tj ET\nendstream\nendobj\n"
          + "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer<</Root 1 0 R/Size 6>>\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
      Files.write(p, pdf);
      LeaveDocument d = new LeaveDocument();
      d.setLeaveId(l.getId()); d.setFileName(name); d.setContentType("application/pdf"); d.setSize(pdf.length); d.setStoragePath(rel);
      leaveDocs.save(d);
    } catch (Exception e) { log.warn("Could not create sample document: {}", e.toString()); }
  }

  private void msg(Student s, Long sender, String text, int minutesAgo) {
    Message m = new Message();
    m.setStudentId(s.getId()); m.setSenderId(sender); m.setContent(text); m.setCreatedAt(java.time.Instant.now().minusSeconds(minutesAgo * 60L));
    if (!sender.equals(s.getUserId())) m.setReadAt(java.time.Instant.now().minusSeconds(minutesAgo * 30L));
    messages.save(m);
  }

  private void feedback(Student s, Faculty f, String text) { MentorFeedback x = new MentorFeedback(); x.setStudentId(s.getId()); x.setFacultyId(f.getId()); x.setContent(text); feedback.save(x); }
  private void observation(Student s, Faculty f, String text) { AcademicObservation x = new AcademicObservation(); x.setStudentId(s.getId()); x.setFacultyId(f.getId()); x.setContent(text); observations.save(x); }

  private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
