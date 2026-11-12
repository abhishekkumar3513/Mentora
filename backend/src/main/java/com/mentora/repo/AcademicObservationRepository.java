package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface AcademicObservationRepository extends JpaRepository<AcademicObservation, Long> { List<AcademicObservation> findByStudentIdOrderByCreatedAtDesc(Long studentId); }
