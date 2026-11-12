package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface LeaveAuditRepository extends JpaRepository<LeaveAudit, Long> { List<LeaveAudit> findByLeaveIdOrderByCreatedAt(Long leaveId); }
