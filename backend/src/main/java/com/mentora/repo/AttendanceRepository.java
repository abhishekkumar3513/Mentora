package com.mentora.repo;
import com.mentora.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
  List<Attendance> findByStudentIdOrderByDate(Long studentId);
  List<Attendance> findByStudentIdInAndDateBetween(Collection<Long> ids, LocalDate from, LocalDate to);
  List<Attendance> findByStudentIdIn(Collection<Long> ids);
  Optional<Attendance> findByStudentIdAndSubjectIdAndDate(Long studentId, Long subjectId, LocalDate date);
  @Modifying @Query("update Attendance a set a.status = com.mentora.domain.AttStatus.L where a.studentId = :sid and a.date between :from and :to and a.status = com.mentora.domain.AttStatus.A")
  int markLeave(@Param("sid") Long studentId, @Param("from") LocalDate from, @Param("to") LocalDate to);
  @Query("select max(a.date) from Attendance a")
  LocalDate latestDate();
}
