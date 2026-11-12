package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface MessageRepository extends JpaRepository<Message, Long> {
  Page<Message> findByStudentId(Long studentId, Pageable p);
  List<Message> findByStudentIdInOrderByCreatedAtDesc(Collection<Long> ids);
  @Query("select m from Message m where m.studentId = :sid and m.senderId <> :uid and m.readAt is null")
  List<Message> unreadFor(@Param("sid") Long studentId, @Param("uid") Long viewerUserId);
  @Query("select count(m) from Message m where m.studentId in :sids and m.senderId <> :uid and m.readAt is null")
  long countUnread(@Param("sids") Collection<Long> sids, @Param("uid") Long viewerUserId);
  @Query("select m.studentId, count(m) from Message m where m.studentId in :sids and m.senderId <> :uid and m.readAt is null group by m.studentId")
  List<Object[]> unreadByStudent(@Param("sids") Collection<Long> sids, @Param("uid") Long viewerUserId);
  @Query("select m.studentId, max(m.createdAt) from Message m where m.studentId in :sids group by m.studentId")
  List<Object[]> lastByStudent(@Param("sids") Collection<Long> sids);
  long countBySenderIdAndCreatedAtAfter(Long senderId, Instant after);
  List<Message> findTop5BySenderIdOrderByCreatedAtDesc(Long senderId);
  @Query(value = "select distinct on (student_id) * from messages where student_id in (:ids) order by student_id, created_at desc", nativeQuery = true)
  List<Message> lastMessages(@Param("ids") Collection<Long> ids);
}
