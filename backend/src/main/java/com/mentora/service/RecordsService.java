package com.mentora.service;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Assignments, IA examination attendance and the instructional calendar. Every change re-evaluates the affected support signals. */
@Service
public class RecordsService {
  private final JdbcTemplate jdbc;
  private final SupportService support;
  private final SupportSignalService signals;
  private final AccessService access;
  private final StudentRepository students;
  private final UserRepository users;

  public RecordsService(JdbcTemplate jdbc, SupportService support, SupportSignalService signals, AccessService access, StudentRepository students, UserRepository users) {
    this.jdbc = jdbc; this.support = support; this.signals = signals; this.access = access; this.students = students; this.users = users;
  }

  private List<Student> scoped(UserPrincipal p) { return students.findByIdIn(support.scope(p)); }
  private Map<Long, String> names(Collection<Student> l) {
    return users.findAllById(l.stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, User::getFullName));
  }

  public List<Map<String, Object>> subjects() { return jdbc.queryForList("select id, name, semester from subjects order by semester, name"); }

  // ----- assignments
  public List<Map<String, Object>> assignments(UserPrincipal p) {
    List<Student> sc = scoped(p);
    var rows = jdbc.queryForList("select a.id, a.title, a.deadline, a.subject_id as \"subjectId\", s.name as subject, s.semester from assignments a join subjects s on s.id = a.subject_id order by a.deadline desc, a.id desc");
    for (var r : rows) {
      int sem = ((Number) r.get("semester")).intValue();
      List<Long> ids = sc.stream().filter(s -> s.getSemester() == sem).map(Student::getId).toList();
      r.put("deadline", r.get("deadline").toString());
      r.put("students", ids.size());
      r.put("submitted", ids.isEmpty() ? 0 : jdbc.queryForObject("select count(*) from assignment_submissions where assignment_id = ? and student_id in (" + csv(ids) + ")", Integer.class, r.get("id")));
    }
    return rows;
  }

  @Transactional
  public Map<String, Object> createAssignment(UserPrincipal p, Long subjectId, String title, String deadline) {
    if (title == null || title.isBlank()) throw ApiException.bad("Title is required");
    LocalDate d = date(deadline);
    var sub = jdbc.queryForList("select semester from subjects where id = ?", subjectId);
    if (sub.isEmpty()) throw ApiException.bad("Unknown subject");
    Long id = jdbc.queryForObject("insert into assignments(subject_id, title, deadline, created_by) values (?,?,?,?) returning id", Long.class, subjectId, title.trim(), java.sql.Date.valueOf(d), p.id());
    int sem = ((Number) sub.get(0).get("semester")).intValue();
    signals.evaluateNow(scoped(p).stream().filter(s -> s.getSemester() == sem).map(Student::getId).toList());
    return Map.of("id", id);
  }

  public List<Map<String, Object>> submissions(UserPrincipal p, Long assignmentId) {
    var a = jdbc.queryForList("select s.semester from assignments x join subjects s on s.id = x.subject_id where x.id = ?", assignmentId);
    if (a.isEmpty()) throw ApiException.notFound("Assignment not found");
    int sem = ((Number) a.get(0).get("semester")).intValue();
    List<Student> sc = scoped(p).stream().filter(s -> s.getSemester() == sem).sorted(Comparator.comparing(Student::getUsn)).toList();
    Map<Long, String> nm = names(sc);
    Set<Long> done = new HashSet<>(jdbc.queryForList("select student_id from assignment_submissions where assignment_id = ?", Long.class, assignmentId));
    return sc.stream().map(s -> row("studentId", s.getId(), "name", nm.get(s.getUserId()), "usn", s.getUsn(), "submitted", done.contains(s.getId()))).toList();
  }

  @Transactional
  public void setSubmission(UserPrincipal p, Long assignmentId, Long studentId, boolean submitted) {
    Student s = access.requireStudentAccess(p, studentId);
    if (jdbc.queryForObject("select count(*) from assignments where id = ?", Integer.class, assignmentId) == 0) throw ApiException.notFound("Assignment not found");
    if (submitted) jdbc.update("insert into assignment_submissions(assignment_id, student_id) values (?,?) on conflict do nothing", assignmentId, studentId);
    else jdbc.update("delete from assignment_submissions where assignment_id = ? and student_id = ?", assignmentId, studentId);
    signals.evaluateNow(List.of(s.getId()));
  }

  // ----- IA examinations
  public Map<String, Object> ia(UserPrincipal p, int semester) {
    List<Map<String, Object>> exams = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      var l = jdbc.queryForList("select id, exam_date, conducted from ia_exams where semester = ? and ia_number = ?", semester, i);
      exams.add(l.isEmpty() ? row("ia", i, "examDate", null, "conducted", false) : row("ia", i, "examDate", l.get(0).get("exam_date") == null ? null : l.get(0).get("exam_date").toString(), "conducted", l.get(0).get("conducted")));
    }
    List<Student> sc = scoped(p).stream().filter(s -> s.getSemester() == semester).sorted(Comparator.comparing(Student::getUsn)).toList();
    Map<Long, String> nm = names(sc);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Student s : sc) {
      Map<Integer, String> st = new TreeMap<>(Map.of(1, "NOT_CONDUCTED", 2, "NOT_CONDUCTED", 3, "NOT_CONDUCTED"));
      jdbc.query("select e.ia_number, e.conducted, a.status from ia_exams e left join ia_exam_attendance a on a.exam_id = e.id and a.student_id = ? where e.semester = ?", rs -> {
        if (rs.getBoolean("conducted")) st.put(rs.getInt("ia_number"), rs.getString("status") == null ? "NOT_RECORDED" : rs.getString("status"));
      }, s.getId(), semester);
      rows.add(row("studentId", s.getId(), "name", nm.get(s.getUserId()), "usn", s.getUsn(), "ia1", st.get(1), "ia2", st.get(2), "ia3", st.get(3)));
    }
    return row("semester", semester, "exams", exams, "students", rows);
  }

  @Transactional
  public void setExam(UserPrincipal p, int semester, int iaNumber, String examDate, boolean conducted) {
    if (iaNumber < 1 || iaNumber > 3) throw ApiException.bad("IA number must be 1, 2 or 3");
    LocalDate d = examDate == null || examDate.isBlank() ? null : date(examDate);
    if (conducted && d != null && d.isAfter(LocalDate.now())) throw ApiException.bad("An examination dated in the future cannot be marked as conducted");
    jdbc.update("insert into ia_exams(semester, ia_number, exam_date, conducted) values (?,?,?,?) on conflict (semester, ia_number) do update set exam_date = excluded.exam_date, conducted = excluded.conducted",
        semester, iaNumber, d == null ? null : java.sql.Date.valueOf(d), conducted);
    signals.evaluateNow(scoped(p).stream().filter(s -> s.getSemester() == semester).map(Student::getId).toList());
  }

  /** status = ATTENDED | ABSENT, or null to clear the record. Attendance is independent of marks. */
  @Transactional
  public void setIaAttendance(UserPrincipal p, int iaNumber, Long studentId, String status) {
    Student s = access.requireStudentAccess(p, studentId);
    var ex = jdbc.queryForList("select id, conducted from ia_exams where semester = ? and ia_number = ?", s.getSemester(), iaNumber);
    if (ex.isEmpty() || !(Boolean) ex.get(0).get("conducted")) throw ApiException.bad("IA " + iaNumber + " has not been marked as conducted for semester " + s.getSemester());
    Long examId = ((Number) ex.get(0).get("id")).longValue();
    if (status == null || status.isBlank()) jdbc.update("delete from ia_exam_attendance where exam_id = ? and student_id = ?", examId, studentId);
    else {
      if (!status.equals("ATTENDED") && !status.equals("ABSENT")) throw ApiException.bad("Status must be ATTENDED or ABSENT");
      jdbc.update("insert into ia_exam_attendance(exam_id, student_id, status) values (?,?,?) on conflict (exam_id, student_id) do update set status = excluded.status", examId, studentId, status);
    }
    signals.evaluateNow(List.of(studentId));
  }

  // ----- calendar
  public List<Map<String, Object>> holidays() { return jdbc.queryForList("select holiday_date::text as date, name from holidays order by holiday_date"); }

  @Transactional
  public void addHoliday(UserPrincipal p, String date, String name) {
    LocalDate d = date(date);
    jdbc.update("insert into holidays(holiday_date, name) values (?,?) on conflict (holiday_date) do update set name = excluded.name", java.sql.Date.valueOf(d), name == null || name.isBlank() ? "Holiday" : name.trim());
    signals.evaluateNow(support.scope(p));
  }

  @Transactional
  public void removeHoliday(UserPrincipal p, String date) {
    jdbc.update("delete from holidays where holiday_date = ?", java.sql.Date.valueOf(date(date)));
    signals.evaluateNow(support.scope(p));
  }

  private static LocalDate date(String s) { try { return LocalDate.parse(s); } catch (Exception e) { throw ApiException.bad("Invalid date (use yyyy-mm-dd)"); } }
  private static String csv(Collection<Long> ids) { return ids.stream().map(String::valueOf).collect(Collectors.joining(",")); }
  private static Map<String, Object> row(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }
}
