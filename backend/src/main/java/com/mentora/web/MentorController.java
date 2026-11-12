package com.mentora.web;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.service.*;
import com.mentora.service.AnalyticsService.StudentMetrics;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Mentor-only endpoints. All data is scoped to the mentor's actively assigned students by construction. */
@RestController
@RequestMapping("/api/mentor")
public class MentorController {
  private final AccessService access;
  private final AnalyticsService analytics;
  private final AttendanceRepository attendance;
  private final MarkRepository marks;
  private final SubjectRepository subjects;
  private final StudentRepository students;
  private final UserRepository users;
  private final AttendanceCalc calc;
  private final MarksCalculator marksCalc;
  private final LeaveService leaveService;
  private final NotificationService notifications;
  private final MessageService messageService;
  private final SettingsService settings;
  private final RosterService roster;

  public MentorController(AccessService access, AnalyticsService analytics, AttendanceRepository attendance, MarkRepository marks, SubjectRepository subjects,
                          StudentRepository students, UserRepository users, AttendanceCalc calc, MarksCalculator marksCalc, LeaveService leaveService,
                          NotificationService notifications, MessageService messageService, SettingsService settings, RosterService roster) {
    this.roster = roster;
    this.settings = settings;
    this.access = access; this.analytics = analytics; this.attendance = attendance; this.marks = marks; this.subjects = subjects; this.students = students;
    this.users = users; this.calc = calc; this.marksCalc = marksCalc; this.leaveService = leaveService; this.notifications = notifications; this.messageService = messageService;
  }

  private Faculty me() { return access.facultyOf(access.current()); }

  @GetMapping("/dashboard")
  public Map<String, Object> dashboard() {
    UserPrincipal p = access.current();
    Faculty f = access.facultyOf(p);
    List<Long> ids = access.assignedStudentIds(f);
    List<StudentMetrics> ms = analytics.metrics(ids);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("totalStudents", ms.size());
    out.put("presentToday", ms.stream().filter(m -> m.todayStatus().equals("P") || m.todayStatus().equals("OD")).count());
    out.put("absentToday", ms.stream().filter(m -> m.todayStatus().equals("A")).count());
    out.put("lowAttendance", ms.stream().filter(StudentMetrics::lowAttendance).count());
    out.put("declining", ms.stream().filter(StudentMetrics::declining).count());
    out.put("pendingLeaves", ms.stream().filter(m -> m.pendingLeaves() > 0).count());
    out.put("unreadMessages", messageService.unreadCount(p));
    out.put("needAttention", ms.stream().filter(StudentMetrics::needsAttention).count());
    out.put("attentionList", ms.stream().filter(StudentMetrics::needsAttention).limit(8).toList());
    out.put("recentActivity", notifications.list(p.id(), 0, 8));
    out.put("referenceDate", analytics.referenceDate());
    return out;
  }

  @GetMapping("/students")
  public Map<String, Object> students(@RequestParam(required = false) String search, @RequestParam(defaultValue = "usn") String sort,
                                      @RequestParam(defaultValue = "asc") String dir, @RequestParam(defaultValue = "ALL") String filter,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    List<StudentMetrics> ms = analytics.metrics(access.assignedStudentIds(me()));
    String q = search == null ? "" : search.trim().toLowerCase();
    var stream = ms.stream().filter(m -> q.isEmpty() || m.name().toLowerCase().contains(q) || m.usn().toLowerCase().contains(q));
    stream = switch (filter.toUpperCase()) {
      case "ATTENTION" -> stream.filter(StudentMetrics::needsAttention);
      case "LOW_ATTENDANCE" -> stream.filter(StudentMetrics::lowAttendance);
      case "DECLINING" -> stream.filter(StudentMetrics::declining);
      case "PENDING_LEAVE" -> stream.filter(m -> m.pendingLeaves() > 0);
      case "EXCELLENT" -> stream.filter(m -> m.status().equals("EXCELLENT"));
      default -> stream;
    };
    Comparator<StudentMetrics> cmp = switch (sort) {
      case "name" -> Comparator.comparing(m -> m.name().toLowerCase());
      case "attendance" -> Comparator.comparingDouble(StudentMetrics::attendancePct);
      case "ia1" -> Comparator.comparingDouble(m -> m.ia1() == null ? -1 : m.ia1());
      case "ia2" -> Comparator.comparingDouble(m -> m.ia2() == null ? -1 : m.ia2());
      case "ia3" -> Comparator.comparingDouble(m -> m.ia3() == null ? -1 : m.ia3());
      case "cgpa" -> Comparator.comparingDouble(m -> m.cgpa() == null ? -1 : m.cgpa());
      case "semester" -> Comparator.comparingInt(StudentMetrics::semester);
      default -> Comparator.comparing(StudentMetrics::usn);
    };
    if (dir.equalsIgnoreCase("desc")) cmp = cmp.reversed();
    List<StudentMetrics> all = stream.sorted(cmp).toList();
    int sz = Math.min(Math.max(size, 1), 100), pg = Math.max(page, 0);
    List<StudentMetrics> slice = all.stream().skip((long) pg * sz).limit(sz).toList();
    return Map.of("content", slice, "total", all.size(), "page", pg, "size", sz, "totalPages", (int) Math.ceil(all.size() / (double) sz));
  }

  /** ERP-style consolidated attendance grid for the mentor's students. */
  @GetMapping("/attendance-grid")
  public Map<String, Object> attendanceGrid(@RequestParam(defaultValue = "20") int days, @RequestParam(defaultValue = "usn") String sort) {
    List<Long> ids = access.assignedStudentIds(me());
    LocalDate ref = analytics.referenceDate();
    List<Attendance> recent = ids.isEmpty() ? List.of() : attendance.findByStudentIdInAndDateBetween(ids, ref.minusDays(Math.min(Math.max(days, 1), 60) * 2L), ref);
    Map<Long, List<Attendance>> by = recent.stream().collect(Collectors.groupingBy(Attendance::getStudentId));
    TreeSet<LocalDate> dates = recent.stream().map(Attendance::getDate).collect(Collectors.toCollection(TreeSet::new));
    List<LocalDate> cols = new ArrayList<>(dates);
    cols = cols.subList(Math.max(0, cols.size() - Math.min(Math.max(days, 1), 60)), cols.size());
    List<LocalDate> finalCols = cols;
    Map<Long, List<Attendance>> allAtt = ids.isEmpty() ? Map.of() : attendance.findByStudentIdIn(ids).stream().collect(Collectors.groupingBy(Attendance::getStudentId));
    List<StudentMetrics> ms = analytics.metrics(ids);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (StudentMetrics m : ms) {
      var daily = calc.daily(by.getOrDefault(m.studentId(), List.of())).stream().collect(Collectors.toMap(AttendanceCalc.DayStatus::date, d -> d.status().name()));
      var full = calc.daily(allAtt.getOrDefault(m.studentId(), List.of()));
      long totalDays = full.stream().filter(d -> d.status() != AttStatus.NC).count();
      long present = full.stream().filter(d -> d.status() == AttStatus.P || d.status() == AttStatus.OD || (d.status() == AttStatus.L)).count();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("studentId", m.studentId()); row.put("name", m.name()); row.put("usn", m.usn());
      row.put("total", totalDays); row.put("present", present); row.put("percent", m.attendancePct());
      Map<String, String> st = new LinkedHashMap<>();
      finalCols.forEach(d -> st.put(d.toString(), daily.getOrDefault(d, "")));
      row.put("days", st);
      rows.add(row);
    }
    rows.sort("name".equals(sort) ? Comparator.comparing(r -> ((String) r.get("name")).toLowerCase()) : Comparator.comparing(r -> (String) r.get("usn")));
    return Map.of("dates", finalCols, "rows", rows, "threshold", settings.num("attendance.threshold", 75));
  }

  /** ERP-style consolidated internal-marks grid for one subject across the mentor's students. */
  @GetMapping("/marks-grid")
  public Map<String, Object> marksGrid(@RequestParam(required = false) Long subjectId, @RequestParam(defaultValue = "usn") String sort) {
    List<Long> ids = access.assignedStudentIds(me());
    Set<Integer> sems = students.findByIdIn(ids).stream().map(Student::getSemester).collect(Collectors.toSet());
    List<Subject> subs = subjects.findAll().stream().filter(s -> sems.contains(s.getSemester())).sorted(Comparator.comparing(Subject::getCode)).toList();
    if (subs.isEmpty()) return Map.of("subjects", List.of(), "rows", List.of(), "rules", marksCalc.rules());
    Subject sel = subs.stream().filter(s -> s.getId().equals(subjectId)).findFirst().orElse(subs.get(0));
    Map<Long, Student> st = students.findByIdIn(ids).stream().collect(Collectors.toMap(Student::getId, Function.identity()));
    Map<Long, User> um = users.findAllById(st.values().stream().map(Student::getUserId).toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    Map<Long, List<Mark>> byStudent = marks.findByStudentIdIn(ids).stream().filter(m -> m.getSubjectId().equals(sel.getId())).collect(Collectors.groupingBy(Mark::getStudentId));
    var rules = marksCalc.rules();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Student s : st.values()) {
      if (s.getSemester() != sel.getSemester()) continue;
      var calcd = marksCalc.compute(byStudent.getOrDefault(s.getId(), List.of()), rules).get(sel.getId());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("studentId", s.getId()); row.put("name", um.get(s.getUserId()).getFullName()); row.put("usn", s.getUsn()); row.put("marks", calcd);
      rows.add(row);
    }
    rows.sort("name".equals(sort) ? Comparator.comparing(r -> ((String) r.get("name")).toLowerCase()) : Comparator.comparing(r -> (String) r.get("usn")));
    return Map.of("subjects", subs.stream().map(s -> Map.of("id", s.getId(), "code", s.getCode(), "name", s.getName())).toList(),
        "selected", Map.of("id", sel.getId(), "code", sel.getCode(), "name", sel.getName()), "rows", rows, "rules", rules);
  }

  // ----- roster management (own students only) -----
  public record AddStudentRequest(String usn) {}
  public record MarkRequest(LocalDate date, List<RosterService.MarkEntry> entries) {}

  @GetMapping("/students/available")
  public List<RosterService.AvailableStudent> available(@RequestParam(required = false) String search) { access.facultyOf(access.current()); return roster.available(search); }

  @PostMapping("/students")
  public RosterService.AvailableStudent addStudent(@RequestBody AddStudentRequest r) { return roster.add(access.current(), r.usn()); }

  @DeleteMapping("/students/{id}")
  public Map<String, Object> removeStudent(@PathVariable Long id) { roster.remove(access.current(), id); return Map.of("ok", true); }

  // ----- attendance editing (own students only) -----
  @GetMapping("/attendance/day")
  public List<RosterService.DayEntry> attendanceDay(@RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date) {
    return roster.day(access.current(), date == null ? LocalDate.now() : date);
  }

  @PostMapping("/attendance/mark")
  public RosterService.MarkResult markAttendance(@RequestBody MarkRequest r) { return roster.mark(access.current(), r.date(), r.entries()); }

  @GetMapping("/leaves")
  public List<LeaveService.LeaveDto> leaves(@RequestParam(required = false) LeaveStatus status) { return leaveService.forMentor(me(), status); }

  @GetMapping("/conversations")
  public List<MessageService.ConversationDto> conversations() { UserPrincipal p = access.current(); return messageService.conversations(access.facultyOf(p), p); }
}
