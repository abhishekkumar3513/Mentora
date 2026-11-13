package com.mentora.service;

import com.mentora.domain.*;
import com.mentora.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Backend-side attendance monitoring: decides which mentor alerts to raise. De-duplicated through notification dedupe keys. */
@Service
public class AttendanceAlertService {
  private final StudentRepository students;
  private final UserRepository users;
  private final AttendanceRepository attendance;
  private final AccessService access;
  private final AttendanceCalc calc;
  private final SettingsService settings;
  private final NotificationService notifications;
  private final SupportSignalService signals;

  public AttendanceAlertService(StudentRepository students, UserRepository users, AttendanceRepository attendance, AccessService access,
                                AttendanceCalc calc, SettingsService settings, NotificationService notifications, SupportSignalService signals) {
    this.signals = signals;
    this.students = students; this.users = users; this.attendance = attendance; this.access = access; this.calc = calc;
    this.settings = settings; this.notifications = notifications;
  }

  /** Evaluate alerts for these students as of the given date. Returns the number of new notifications created. */
  @Transactional
  public int evaluate(Collection<Long> studentIds, LocalDate date) {
    int created = 0;
    double declinePts = settings.num("attendance.decline_points", 20);
    for (Student s : students.findByIdIn(studentIds)) {
      Faculty mentor = access.mentorOf(s.getId());
      if (mentor == null) continue;
      String name = users.findById(s.getUserId()).map(User::getFullName).orElse("Student");
      String label = name + " (" + s.getUsn() + ")";
      List<Attendance> all = attendance.findByStudentIdOrderByDate(s.getId());
      var daily = calc.daily(all);
      AttStatus today = daily.stream().filter(d -> d.date().equals(date)).map(AttendanceCalc.DayStatus::status).findFirst().orElse(AttStatus.NC);
      String link = "/mentor/students/" + s.getId();
      if (today == AttStatus.A) {
        if (notifications.notify(mentor.getUserId(), NotificationType.ABSENCE, "Attendance Alert",
            "Attendance Alert: " + label + " was marked absent on " + date + ".", link, "ABS:" + s.getId() + ":" + date)) created++;
      }
      // significant decline: last 7 days vs the 21 days before that
      List<Attendance> recent = all.stream().filter(a -> !a.getDate().isAfter(date) && a.getDate().isAfter(date.minusDays(7))).toList();
      List<Attendance> before = all.stream().filter(a -> !a.getDate().isAfter(date.minusDays(7)) && a.getDate().isAfter(date.minusDays(28))).toList();
      var r = calc.summarize(recent); var b = calc.summarize(before);
      if (r.conducted() >= 3 && b.conducted() >= 5 && b.percent() - r.percent() >= declinePts
          && notifications.notify(mentor.getUserId(), NotificationType.ATTENDANCE_DECLINE, "Attendance decline",
          label + "'s attendance dropped from " + b.percent() + "% to " + r.percent() + "% over the last week.", link, "DECL:" + s.getId() + ":" + date)) created++;
    }
    // support signals (attendance threshold, consecutive absence, assignments, IA attendance) are owned by SupportSignalService
    created += signals.evaluate(studentIds, LocalDate.now());
    return created;
  }

  /** Alerts the mentor when internal marks of a student have declined between consecutive tests. */
  @Transactional
  public int evaluateAcademic(Collection<AnalyticsService.StudentMetrics> metrics) {
    int created = 0;
    Map<Long, Student> st = students.findByIdIn(metrics.stream().map(AnalyticsService.StudentMetrics::studentId).toList()).stream().collect(Collectors.toMap(Student::getId, s -> s));
    for (var m : metrics) {
      if (!m.declining()) continue;
      Faculty mentor = access.mentorOf(m.studentId());
      if (mentor == null) continue;
      String latest = m.ia3() != null ? "IA 3" : "IA 2";
      if (notifications.notify(mentor.getUserId(), NotificationType.ACADEMIC_DECLINE, "Declining academic performance",
          m.name() + " (" + m.usn() + ") shows declining internal marks as of " + latest + ".", "/mentor/students/" + m.studentId(), "ACAD:" + m.studentId() + ":" + latest)) created++;
    }
    return created;
  }
}
