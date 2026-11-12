package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
  Page<Notification> findByUserId(Long userId, Pageable p);
  long countByUserIdAndReadFalse(Long userId);
  boolean existsByUserIdAndDedupeKey(Long userId, String key);
  @Modifying @Query("update Notification n set n.read = true where n.userId = :uid and n.read = false")
  int markAllRead(@Param("uid") Long userId);
}
