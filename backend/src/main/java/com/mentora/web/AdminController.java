package com.mentora.web;

import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  public record AssignRequest(Long studentId, Long facultyId) {}
  public record DepartmentRequest(String code, String name) {}
  public record SemesterRequest(Integer number, String academicYear, Boolean current) {}
  public record SettingRequest(String value) {}
  public record MarkDayRequest(String usn, LocalDate date, AttStatus status) {}

  private final AdminService admin;
  private final ImportService importer;
  private final SettingsService settings;
  private final DepartmentRepository departments;
  private final SemesterRepository semesters;
  private final SubjectRepository subjects;

  public AdminController(AdminService admin, ImportService importer, SettingsService settings, DepartmentRepository departments, SemesterRepository semesters, SubjectRepository subjects) {
    this.admin = admin; this.importer = importer; this.settings = settings; this.departments = departments; this.semesters = semesters; this.subjects = subjects;
  }

  @GetMapping("/stats") public Map<String, Object> stats() { return admin.stats(); }

  @GetMapping("/students") public List<AdminService.StudentRow> students(@RequestParam(required = false) String search) { return admin.listStudents(search); }
  @PostMapping("/students") public AdminService.StudentRow createStudent(@RequestBody AdminService.StudentInput in) { return admin.createStudent(in); }
  @PutMapping("/students/{id}") public AdminService.StudentRow updateStudent(@PathVariable Long id, @RequestBody AdminService.StudentInput in) { return admin.updateStudent(id, in); }

  @GetMapping("/mentors") public List<AdminService.MentorRow> mentors() { return admin.listMentors(); }
  @PostMapping("/mentors") public AdminService.MentorRow createMentor(@RequestBody AdminService.MentorInput in) { return admin.createMentor(in); }
  @PutMapping("/mentors/{id}") public AdminService.MentorRow updateMentor(@PathVariable Long id, @RequestBody AdminService.MentorInput in) { return admin.updateMentor(id, in); }

  @PostMapping("/assignments") public Map<String, Object> assign(@RequestBody AssignRequest r) { admin.assign(r.studentId(), r.facultyId()); return Map.of("ok", true); }
  @DeleteMapping("/assignments/{studentId}") public Map<String, Object> unassign(@PathVariable Long studentId) { admin.unassign(studentId); return Map.of("ok", true); }

  @GetMapping("/departments") public List<Department> departments() { return departments.findAll(); }
  @PostMapping("/departments") public Department createDepartment(@RequestBody DepartmentRequest r) { return admin.saveDepartment(null, r.code(), r.name()); }
  @PutMapping("/departments/{id}") public Department updateDepartment(@PathVariable Long id, @RequestBody DepartmentRequest r) { return admin.saveDepartment(id, r.code(), r.name()); }

  @GetMapping("/semesters") public List<Semester> semesters() { return semesters.findAll().stream().sorted(java.util.Comparator.comparingInt(Semester::getNumber)).toList(); }
  @PostMapping("/semesters") public Semester createSemester(@RequestBody SemesterRequest r) { return admin.saveSemester(null, r.number(), r.academicYear(), r.current()); }
  @PutMapping("/semesters/{id}") public Semester updateSemester(@PathVariable Long id, @RequestBody SemesterRequest r) { return admin.saveSemester(id, r.number(), r.academicYear(), r.current()); }

  @GetMapping("/subjects") public List<Subject> subjects() { return subjects.findAll().stream().sorted(java.util.Comparator.comparingInt(Subject::getSemester).thenComparing(Subject::getCode)).toList(); }
  @PostMapping("/subjects") public Subject createSubject(@RequestBody AdminService.SubjectInput in) { return admin.saveSubject(null, in); }
  @PutMapping("/subjects/{id}") public Subject updateSubject(@PathVariable Long id, @RequestBody AdminService.SubjectInput in) { return admin.saveSubject(id, in); }
  @DeleteMapping("/subjects/{id}") public Map<String, Object> deleteSubject(@PathVariable Long id) { admin.deleteSubject(id); return Map.of("ok", true); }

  @GetMapping("/settings") public List<Setting> settings() { return List.copyOf(settings.all().values()); }
  @PutMapping("/settings/{key}") public Setting updateSetting(@PathVariable String key, @RequestBody SettingRequest r) { return settings.update(key, r.value()); }

  @PostMapping(value = "/import/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ImportService.Report importData(@PathVariable String type, @RequestParam MultipartFile file) {
    if (!List.of("attendance", "marks", "results").contains(type)) throw ApiException.bad("Unknown import type");
    return importer.importFile(type, file);
  }

  /** Marks one day for all subjects of a student; the backend decides on mentor alerts. */
  @PostMapping("/attendance/mark-day")
  public Map<String, Object> markDay(@RequestBody MarkDayRequest r) {
    if (r.status() == null) throw ApiException.bad("Status is required");
    int alerts = importer.markDay(r.usn(), r.date(), r.status());
    return Map.of("ok", true, "alertsCreated", alerts);
  }
}
