package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface MentorAssignmentRepository extends JpaRepository<MentorAssignment, Long> {
  Optional<MentorAssignment> findByStudentIdAndActiveTrue(Long studentId);
  List<MentorAssignment> findByFacultyIdAndActiveTrue(Long facultyId);
  List<MentorAssignment> findByActiveTrue();
  boolean existsByStudentIdAndFacultyIdAndActiveTrue(Long studentId, Long facultyId);
}
