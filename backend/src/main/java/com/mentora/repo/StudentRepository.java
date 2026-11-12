package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface StudentRepository extends JpaRepository<Student, Long> {
  Optional<Student> findByUserId(Long userId);
  Optional<Student> findByUsnIgnoreCase(String usn);
  List<Student> findByIdIn(Collection<Long> ids);
}
