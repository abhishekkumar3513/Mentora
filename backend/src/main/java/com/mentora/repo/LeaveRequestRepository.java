package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
  List<LeaveRequest> findByStudentIdOrderByCreatedAtDesc(Long studentId);
  List<LeaveRequest> findByStudentIdInOrderByCreatedAtDesc(Collection<Long> ids);
  List<LeaveRequest> findByStudentIdInAndStatus(Collection<Long> ids, LeaveStatus status);
  long countByStudentIdAndStatus(Long studentId, LeaveStatus status);
}
