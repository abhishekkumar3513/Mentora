package com.mentora.service;

import com.mentora.domain.AttStatus;
import com.mentora.domain.Attendance;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** Attendance calculations from real records: class-level percentage and derived daily status. */
@Service
public class AttendanceCalc {
  public record Summary(int present, int absent, int onDuty, int leave, int notConducted, int conducted, int attended, double percent) {}
  public record DayStatus(LocalDate date, AttStatus status) {}

  private final SettingsService settings;

  public AttendanceCalc(SettingsService settings) { this.settings = settings; }

  public Summary summarize(Collection<Attendance> records) {
    int p = 0, a = 0, od = 0, l = 0, nc = 0;
    for (Attendance r : records) {
      switch (r.getStatus()) { case P -> p++; case A -> a++; case OD -> od++; case L -> l++; case NC -> nc++; }
    }
    int conducted = p + a + od + l;
    int attended = p + od + (settings.bool("attendance.leave_counts_as_present", true) ? l : 0);
    double pct = conducted == 0 ? 0 : Math.round(attended * 1000.0 / conducted) / 10.0;
    return new Summary(p, a, od, l, nc, conducted, attended, pct);
  }

  /** Day status = most frequent status among conducted classes (ties: A > L > OD > P); NC if none conducted. */
  public static AttStatus dayStatus(Collection<Attendance> dayRecords) {
    Map<AttStatus, Integer> cnt = new EnumMap<>(AttStatus.class);
    for (Attendance r : dayRecords) if (r.getStatus() != AttStatus.NC) cnt.merge(r.getStatus(), 1, Integer::sum);
    if (cnt.isEmpty()) return AttStatus.NC;
    AttStatus best = null;
    for (AttStatus s : List.of(AttStatus.A, AttStatus.L, AttStatus.OD, AttStatus.P)) {
      if (cnt.containsKey(s) && (best == null || cnt.get(s) > cnt.get(best))) best = s;
    }
    return best;
  }

  public List<DayStatus> daily(Collection<Attendance> records) {
    Map<LocalDate, List<Attendance>> byDate = new TreeMap<>();
    for (Attendance r : records) byDate.computeIfAbsent(r.getDate(), d -> new ArrayList<>()).add(r);
    List<DayStatus> out = new ArrayList<>();
    byDate.forEach((d, l) -> out.add(new DayStatus(d, dayStatus(l))));
    return out;
  }

  /** Consecutive conducted absent days ending at the most recent conducted day on/before ref. */
  public static int consecutiveAbsent(List<DayStatus> daily, LocalDate ref) {
    int n = 0;
    List<DayStatus> conducted = daily.stream().filter(d -> d.status() != AttStatus.NC && !d.date().isAfter(ref)).toList();
    for (int i = conducted.size() - 1; i >= 0; i--) {
      if (conducted.get(i).status() == AttStatus.A) n++; else break;
    }
    return n;
  }
}
