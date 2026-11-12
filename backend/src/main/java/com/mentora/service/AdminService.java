package com.mentora.service;

import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminService {
  public record StudentRow(Long id, Long userId, String name, String email, String usn, int semester, String section, Long departmentId, String department, Long mentorId, String mentorName, boolean enabled) {}
  public record MentorRow(Long id, Long userId, String name, String email, String employeeId, Long departmentId, String department, String designation, int students, boolean enabled) {}

  private final UserRepository users;
  private final StudentRepository students;
  private final FacultyRepository faculty;
  private final DepartmentRepository departments;
  private final MentorAssignmentRepository assignments;
  private final SubjectRepository subjects;
  private final SemesterRepository semesters;
  private final PasswordEncoder encoder;
  private final AnalyticsService analytics;

  public AdminService(UserRepository users, StudentRepository students, FacultyRepository faculty, DepartmentRepository departments, MentorAssignmentRepository assignments,
                      SubjectRepository subjects, SemesterRepository semesters, PasswordEncoder encoder, AnalyticsService analytics) {
    this.users = users; this.students = students; this.faculty = faculty; this.departments = departments; this.assignments = assignments;
    this.subjects = subjects; this.semesters = semesters; this.encoder = encoder; this.analytics = analytics;
  }

  // ----- students -----
  @Transactional(readOnly = true)
  public List<StudentRow> listStudents(String search) {
    Map<Long, User> um = users.findByRole(Role.STUDENT).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    Map<Long, Department> dm = departments.findAll().stream().collect(Collectors.toMap(Department::getId, Function.identity()));
    Map<Long, MentorAssignment> am = assignments.findByActiveTrue().stream().collect(Collectors.toMap(MentorAssignment::getStudentId, Function.identity()));
    Map<Long, Faculty> fm = faculty.findAll().stream().collect(Collectors.toMap(Faculty::getId, Function.identity()));
    Map<Long, User> allUsers = users.findAll().stream().collect(Collectors.toMap(User::getId, Function.identity()));
    String q = search == null ? "" : search.toLowerCase().trim();
    List<StudentRow> out = new ArrayList<>();
    for (Student s : students.findAll()) {
      User u = um.get(s.getUserId());
      if (u == null) continue;
      if (!q.isEmpty() && !(u.getFullName().toLowerCase().contains(q) || s.getUsn().toLowerCase().contains(q))) continue;
      MentorAssignment a = am.get(s.getId());
      Faculty f = a == null ? null : fm.get(a.getFacultyId());
      out.add(new StudentRow(s.getId(), u.getId(), u.getFullName(), u.getEmail(), s.getUsn(), s.getSemester(), s.getSection(), s.getDepartmentId(),
          dm.get(s.getDepartmentId()).getName(), f == null ? null : f.getId(), f == null ? null : allUsers.get(f.getUserId()).getFullName(), u.isEnabled()));
    }
    out.sort(Comparator.comparing(StudentRow::usn));
    return out;
  }

  public record StudentInput(String name, String email, String password, String usn, Integer semester, String section, Long departmentId, String phone, Boolean enabled) {}

  @Transactional
  public StudentRow createStudent(StudentInput in) {
    requireText(in.name(), "Name"); requireText(in.email(), "Email"); requireText(in.usn(), "USN");
    if (in.password() == null || in.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
    if (in.semester() == null || in.semester() < 1 || in.semester() > 8) throw ApiException.bad("Semester must be between 1 and 8");
    if (in.departmentId() == null) throw ApiException.bad("Department is required");
    if (users.existsByEmailIgnoreCase(in.email())) throw ApiException.bad("Email already in use");
    if (students.findByUsnIgnoreCase(in.usn()).isPresent()) throw ApiException.bad("USN already exists");
    departments.findById(in.departmentId()).orElseThrow(() -> ApiException.bad("Unknown department"));
    User u = newUser(in.name(), in.email(), in.password(), Role.STUDENT, in.phone());
    Student s = new Student();
    s.setUserId(u.getId()); s.setUsn(in.usn().trim().toUpperCase()); s.setSemester(in.semester()); s.setSection(in.section()); s.setDepartmentId(in.departmentId());
    students.save(s);
    return listStudents(s.getUsn()).get(0);
  }

  @Transactional
  public StudentRow updateStudent(Long id, StudentInput in) {
    Student s = students.findById(id).orElseThrow(() -> ApiException.notFound("Student not found"));
    User u = users.findById(s.getUserId()).orElseThrow();
    if (in.name() != null && !in.name().isBlank()) u.setFullName(in.name().trim());
    if (in.phone() != null) u.setPhone(in.phone());
    if (in.email() != null && !in.email().equalsIgnoreCase(u.getEmail())) {
      if (users.existsByEmailIgnoreCase(in.email())) throw ApiException.bad("Email already in use");
      u.setEmail(in.email().trim());
    }
    if (in.password() != null && !in.password().isBlank()) {
      if (in.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
      u.setPasswordHash(encoder.encode(in.password()));
    }
    if (in.enabled() != null) u.setEnabled(in.enabled());
    if (in.semester() != null) { if (in.semester() < 1 || in.semester() > 8) throw ApiException.bad("Semester must be between 1 and 8"); s.setSemester(in.semester()); }
    if (in.section() != null) s.setSection(in.section());
    if (in.departmentId() != null) s.setDepartmentId(in.departmentId());
    u.setUpdatedAt(Instant.now());
    return listStudents(s.getUsn()).get(0);
  }

  // ----- mentors -----
  public record MentorInput(String name, String email, String password, String employeeId, Long departmentId, String designation, String phone, Boolean enabled) {}

  @Transactional(readOnly = true)
  public List<MentorRow> listMentors() {
    Map<Long, User> um = users.findAll().stream().collect(Collectors.toMap(User::getId, Function.identity()));
    Map<Long, Department> dm = departments.findAll().stream().collect(Collectors.toMap(Department::getId, Function.identity()));
    Map<Long, Long> counts = assignments.findByActiveTrue().stream().collect(Collectors.groupingBy(MentorAssignment::getFacultyId, Collectors.counting()));
    return faculty.findAll().stream().map(f -> new MentorRow(f.getId(), f.getUserId(), um.get(f.getUserId()).getFullName(), um.get(f.getUserId()).getEmail(), f.getEmployeeId(),
        f.getDepartmentId(), dm.get(f.getDepartmentId()).getName(), f.getDesignation(), counts.getOrDefault(f.getId(), 0L).intValue(), um.get(f.getUserId()).isEnabled()))
        .sorted(Comparator.comparing(MentorRow::name)).toList();
  }

  @Transactional
  public MentorRow createMentor(MentorInput in) {
    requireText(in.name(), "Name"); requireText(in.email(), "Email"); requireText(in.employeeId(), "Employee ID");
    if (in.password() == null || in.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
    if (in.departmentId() == null) throw ApiException.bad("Department is required");
    if (users.existsByEmailIgnoreCase(in.email())) throw ApiException.bad("Email already in use");
    departments.findById(in.departmentId()).orElseThrow(() -> ApiException.bad("Unknown department"));
    User u = newUser(in.name(), in.email(), in.password(), Role.MENTOR, in.phone());
    Faculty f = new Faculty();
    f.setUserId(u.getId()); f.setDepartmentId(in.departmentId()); f.setEmployeeId(in.employeeId().trim()); f.setDesignation(in.designation());
    faculty.save(f);
    return listMentors().stream().filter(m -> m.id().equals(f.getId())).findFirst().orElseThrow();
  }

  @Transactional
  public MentorRow updateMentor(Long id, MentorInput in) {
    Faculty f = faculty.findById(id).orElseThrow(() -> ApiException.notFound("Mentor not found"));
    User u = users.findById(f.getUserId()).orElseThrow();
    if (in.name() != null && !in.name().isBlank()) u.setFullName(in.name().trim());
    if (in.phone() != null) u.setPhone(in.phone());
    if (in.email() != null && !in.email().isBlank() && !in.email().equalsIgnoreCase(u.getEmail())) {
      if (users.existsByEmailIgnoreCase(in.email())) throw ApiException.bad("Email already in use");
      u.setEmail(in.email().trim().toLowerCase());
    }
    if (in.password() != null && !in.password().isBlank()) {
      if (in.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
      u.setPasswordHash(encoder.encode(in.password()));
    }
    if (in.enabled() != null) u.setEnabled(in.enabled());
    if (in.designation() != null) f.setDesignation(in.designation());
    if (in.departmentId() != null) f.setDepartmentId(in.departmentId());
    u.setUpdatedAt(Instant.now());
    return listMentors().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
  }

  /** (Re)assigns a student to a mentor, deactivating the previous assignment. */
  @Transactional
  public void assign(Long studentId, Long facultyId) {
    students.findById(studentId).orElseThrow(() -> ApiException.notFound("Student not found"));
    faculty.findById(facultyId).orElseThrow(() -> ApiException.notFound("Mentor not found"));
    assignments.findByStudentIdAndActiveTrue(studentId).ifPresent(a -> {
      if (a.getFacultyId().equals(facultyId)) throw ApiException.bad("Student is already assigned to this mentor");
      a.setActive(false);
      assignments.saveAndFlush(a);
    });
    MentorAssignment n = new MentorAssignment();
    n.setStudentId(studentId); n.setFacultyId(facultyId);
    assignments.save(n);
  }

  @Transactional
  public void unassign(Long studentId) {
    assignments.findByStudentIdAndActiveTrue(studentId).ifPresent(a -> a.setActive(false));
  }

  // ----- departments / semesters / subjects -----
  public Department saveDepartment(Long id, String code, String name) {
    requireText(code, "Code"); requireText(name, "Name");
    Department d = id == null ? new Department() : departments.findById(id).orElseThrow(() -> ApiException.notFound("Department not found"));
    d.setCode(code.trim().toUpperCase()); d.setName(name.trim());
    return departments.save(d);
  }

  public Semester saveSemester(Long id, Integer number, String year, Boolean current) {
    if (number == null || number < 1 || number > 8) throw ApiException.bad("Semester number must be 1-8");
    requireText(year, "Academic year");
    Semester s = id == null ? new Semester() : semesters.findById(id).orElseThrow(() -> ApiException.notFound("Semester not found"));
    s.setNumber(number); s.setAcademicYear(year.trim()); s.setCurrent(Boolean.TRUE.equals(current));
    return semesters.save(s);
  }

  public record SubjectInput(String code, String name, Integer credits, Integer semester, Long departmentId) {}

  public Subject saveSubject(Long id, SubjectInput in) {
    requireText(in.code(), "Code"); requireText(in.name(), "Name");
    if (in.credits() == null || in.credits() < 0 || in.credits() > 10) throw ApiException.bad("Credits must be between 0 and 10");
    if (in.semester() == null || in.semester() < 1 || in.semester() > 8) throw ApiException.bad("Semester must be 1-8");
    if (in.departmentId() == null) throw ApiException.bad("Department is required");
    Subject s = id == null ? new Subject() : subjects.findById(id).orElseThrow(() -> ApiException.notFound("Subject not found"));
    s.setCode(in.code().trim().toUpperCase()); s.setName(in.name().trim()); s.setCredits(in.credits()); s.setSemester(in.semester()); s.setDepartmentId(in.departmentId());
    return subjects.save(s);
  }

  public void deleteSubject(Long id) { subjects.deleteById(id); }

  // ----- stats -----
  @Transactional(readOnly = true)
  public Map<String, Object> stats() {
    List<Long> ids = students.findAll().stream().map(Student::getId).toList();
    var ms = analytics.metrics(ids);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("students", ids.size());
    m.put("mentors", faculty.count());
    m.put("departments", departments.count());
    m.put("subjects", subjects.count());
    m.put("unassignedStudents", ids.size() - assignments.findByActiveTrue().size());
    m.put("averageAttendance", ms.isEmpty() ? 0 : Math.round(ms.stream().mapToDouble(AnalyticsService.StudentMetrics::attendancePct).average().orElse(0) * 10) / 10.0);
    m.put("lowAttendance", ms.stream().filter(AnalyticsService.StudentMetrics::lowAttendance).count());
    m.put("needAttention", ms.stream().filter(AnalyticsService.StudentMetrics::needsAttention).count());
    return m;
  }

  private User newUser(String name, String email, String password, Role role, String phone) {
    User u = new User();
    u.setFullName(name.trim()); u.setEmail(email.trim().toLowerCase()); u.setPasswordHash(encoder.encode(password)); u.setRole(role); u.setPhone(phone);
    return users.save(u);
  }

  private static void requireText(String v, String label) { if (v == null || v.isBlank()) throw ApiException.bad(label + " is required"); }
}
