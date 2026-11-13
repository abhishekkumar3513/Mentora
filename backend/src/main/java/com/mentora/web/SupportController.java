package com.mentora.web;

import com.mentora.config.UserPrincipal;
import com.mentora.service.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Overview, support requests / interventions, records. Authorization is enforced in the services (scope + ownership + confidentiality). */
@RestController
@RequestMapping("/api")
public class SupportController {
  public record StudentRequest(@NotBlank String category, @NotBlank @Size(max = 2000) String concern, @Size(max = 2000) String helpNeeded) {}
  public record ActionRequest(Long studentId, @NotBlank String category, String ownerRole, Long ownerUserId, @NotBlank @Size(max = 2000) String observation, Long signalId) {}
  public record AssignmentRequest(Long subjectId, String title, String deadline) {}
  public record SubmissionRequest(boolean submitted) {}
  public record ExamRequest(int semester, int iaNumber, String examDate, boolean conducted) {}
  public record IaAttendanceRequest(int iaNumber, Long studentId, String status) {}
  public record HolidayRequest(String date, String name) {}

  private final AccessService access;
  private final SupportService support;
  private final OverviewService overview;
  private final RecordsService records;

  public SupportController(AccessService access, SupportService support, OverviewService overview, RecordsService records) {
    this.access = access; this.support = support; this.overview = overview; this.records = records;
  }

  private UserPrincipal me() { return access.current(); }

  // ----- department overview (mentor: assigned students, admin: institution)
  @GetMapping("/overview") public Map<String, Object> overview() { return overview.overview(me()); }

  @GetMapping("/overview/students")
  public List<Map<String, Object>> students(@RequestParam(required = false) String category, @RequestParam(required = false) String priority, @RequestParam(required = false) String q) {
    return overview.students(me(), category, priority, q);
  }

  /** Support signals + IA attendance + assignments for one student (mentor of the student, admin, or the student). */
  @GetMapping("/students/{id}/support")
  public Map<String, Object> studentSupport(@PathVariable Long id) {
    UserPrincipal p = me();
    access.requireStudentAccess(p, id);
    Map<String, Object> m = new java.util.LinkedHashMap<>(overview.studentSupport(id, p.role() == com.mentora.domain.Role.STUDENT));
    if (p.role() != com.mentora.domain.Role.STUDENT) m.put("requests", support.listForStaff(p, id));
    return m;
  }

  // ----- student navigator
  @GetMapping("/support/categories") public List<Map<String, Object>> categories() { return support.categories(false); }

  @GetMapping("/support/my-requests") public List<Map<String, Object>> myRequests() { return support.listForStudent(me()); }

  @PostMapping("/support/requests")
  public Map<String, Object> submit(@RequestBody StudentRequest r) { return support.submitAsStudent(me(), r.category(), r.concern(), r.helpNeeded()); }

  // ----- staff interventions
  @GetMapping("/support/requests")
  public List<Map<String, Object>> list(@RequestParam(required = false) Long studentId) { return support.listForStaff(me(), studentId); }

  @GetMapping("/support/requests/{id}") public Map<String, Object> get(@PathVariable Long id) { return support.getForStaff(me(), id); }

  @PostMapping("/support/actions")
  public Map<String, Object> createAction(@RequestBody ActionRequest r) { return support.createAction(me(), r.studentId(), r.category(), r.ownerRole(), r.ownerUserId(), r.observation(), r.signalId()); }

  @PutMapping("/support/requests/{id}")
  public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) { return support.update(me(), id, body); }

  @GetMapping("/support/owners") public Map<String, Object> owners() {
    return Map.of("roles", SupportService.OWNER_ROLES.stream().map(r -> Map.of("code", r, "label", SupportService.roleLabel(r))).toList(), "people", support.ownerCandidates());
  }

  // ----- records (assignments, IA attendance, calendar): mentor for assigned students, admin for all
  @GetMapping("/records/subjects") public List<Map<String, Object>> subjectList() { return records.subjects(); }
  @GetMapping("/records/assignments") public List<Map<String, Object>> assignments() { return records.assignments(me()); }
  @PostMapping("/records/assignments") public Map<String, Object> createAssignment(@RequestBody AssignmentRequest r) { return records.createAssignment(me(), r.subjectId(), r.title(), r.deadline()); }
  @GetMapping("/records/assignments/{id}/submissions") public List<Map<String, Object>> submissions(@PathVariable Long id) { return records.submissions(me(), id); }
  @PutMapping("/records/assignments/{id}/submissions/{studentId}")
  public Map<String, Object> setSubmission(@PathVariable Long id, @PathVariable Long studentId, @RequestBody SubmissionRequest r) { records.setSubmission(me(), id, studentId, r.submitted()); return Map.of("ok", true); }

  @GetMapping("/records/ia") public Map<String, Object> ia(@RequestParam int semester) { return records.ia(me(), semester); }
  @PutMapping("/records/ia/exam") public Map<String, Object> setExam(@RequestBody ExamRequest r) { records.setExam(me(), r.semester(), r.iaNumber(), r.examDate(), r.conducted()); return Map.of("ok", true); }
  @PutMapping("/records/ia/attendance") public Map<String, Object> setIa(@RequestBody IaAttendanceRequest r) { records.setIaAttendance(me(), r.iaNumber(), r.studentId(), r.status()); return Map.of("ok", true); }

  @GetMapping("/records/holidays") public List<Map<String, Object>> holidays() { return records.holidays(); }
  @PostMapping("/records/holidays") public Map<String, Object> addHoliday(@RequestBody HolidayRequest r) { records.addHoliday(me(), r.date(), r.name()); return Map.of("ok", true); }
  @DeleteMapping("/records/holidays/{date}") public Map<String, Object> removeHoliday(@PathVariable String date) { records.removeHoliday(me(), date); return Map.of("ok", true); }
}
