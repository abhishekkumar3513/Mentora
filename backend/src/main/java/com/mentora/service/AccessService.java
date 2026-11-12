package com.mentora.service;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.List;

/** Single place where data-access authorization is decided. Every student-scoped read/write goes through here. */
@Service
public class AccessService {
  private final StudentRepository students;
  private final FacultyRepository faculty;
  private final MentorAssignmentRepository assignments;

  public AccessService(StudentRepository students, FacultyRepository faculty, MentorAssignmentRepository assignments) {
    this.students = students; this.faculty = faculty; this.assignments = assignments;
  }

  public UserPrincipal current() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof UserPrincipal p)) throw ApiException.forbidden("Authentication required");
    return p;
  }

  public Faculty facultyOf(UserPrincipal p) {
    if (p.role() != Role.MENTOR) throw ApiException.forbidden("Mentor role required");
    return faculty.findByUserId(p.id()).orElseThrow(() -> ApiException.forbidden("No faculty profile"));
  }

  public Student studentOf(UserPrincipal p) {
    if (p.role() != Role.STUDENT) throw ApiException.forbidden("Student role required");
    return students.findByUserId(p.id()).orElseThrow(() -> ApiException.forbidden("No student profile"));
  }

  /** IDs of the students actively assigned to the mentor. */
  public List<Long> assignedStudentIds(Faculty f) {
    return assignments.findByFacultyIdAndActiveTrue(f.getId()).stream().map(MentorAssignment::getStudentId).toList();
  }

  /** Throws unless the caller may read this student's records. Returns the student. */
  public Student requireStudentAccess(UserPrincipal p, Long studentId) {
    Student s = students.findById(studentId).orElseThrow(() -> ApiException.forbidden("Access denied"));
    switch (p.role()) {
      case ADMIN -> { }
      case STUDENT -> { if (!s.getUserId().equals(p.id())) throw ApiException.forbidden("Access denied"); }
      case MENTOR -> {
        Faculty f = facultyOf(p);
        if (!assignments.existsByStudentIdAndFacultyIdAndActiveTrue(studentId, f.getId())) throw ApiException.forbidden("Access denied");
      }
    }
    return s;
  }

  /** Mentor of a student (if any). */
  public Faculty mentorOf(Long studentId) {
    return assignments.findByStudentIdAndActiveTrue(studentId).flatMap(a -> faculty.findById(a.getFacultyId())).orElse(null);
  }
}
