package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface ModerationLogRepository extends JpaRepository<ModerationLog, Long> {
  long countByUserIdAndCreatedAtAfter(Long userId, Instant after);
  @Query("select max(l.createdAt) from ModerationLog l where l.userId = :uid")
  Instant lastViolation(@Param("uid") Long userId);
}
