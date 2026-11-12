package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface LeaveDocumentRepository extends JpaRepository<LeaveDocument, Long> {
  List<LeaveDocument> findByLeaveIdIn(Collection<Long> ids);
  List<LeaveDocument> findByLeaveId(Long leaveId);
}
