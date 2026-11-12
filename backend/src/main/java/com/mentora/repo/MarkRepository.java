package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface MarkRepository extends JpaRepository<Mark, Long> {
  List<Mark> findByStudentId(Long studentId);
  List<Mark> findByStudentIdIn(Collection<Long> ids);
  Optional<Mark> findByStudentIdAndSubjectIdAndComponent(Long studentId, Long subjectId, Component c);
}
