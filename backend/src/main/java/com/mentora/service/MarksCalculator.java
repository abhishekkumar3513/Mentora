package com.mentora.service;

import com.mentora.domain.Component;
import com.mentora.domain.Mark;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Reusable internal-assessment calculation service. All rules come from configurable settings:
 * IA bucket = sum of best N IA tests (ERP: best 2 of 3, 50 marks) converted to ia.converted_max (25);
 * activity bucket = sum of activities converted to activity.converted_max.
 */
@Service
public class MarksCalculator {
  public record Rules(double iaMax, int iaBestCount, double iaBucketMax, double iaConvertedMax,
                      double actEach, int actCount, double actBucketMax, double actConvertedMax) {}

  public record SubjectMarks(Long subjectId, Double ia1, Double ia2, Double ia3, Double act1, Double act2, Double act3,
                             double iaBucket, double iaConverted, double actBucket, double actConverted, double total) {}

  private final SettingsService settings;

  public MarksCalculator(SettingsService settings) { this.settings = settings; }

  public Rules rules() {
    return new Rules(settings.num("ia.max_per_test", 25), settings.integer("ia.best_count", 2), settings.num("ia.bucket_max", 50),
        settings.num("ia.converted_max", 25), settings.num("activity.max_each", 10), settings.integer("activity.count", 3),
        settings.num("activity.bucket_max", 30), settings.num("activity.converted_max", 25));
  }

  /** Group marks by subject and compute. */
  public Map<Long, SubjectMarks> compute(Collection<Mark> marks, Rules r) {
    Map<Long, Map<Component, Double>> bySubject = new LinkedHashMap<>();
    for (Mark m : marks) {
      Double v = m.isAbsent() || m.getScore() == null ? Double.valueOf(0) : m.getScore().doubleValue();
      bySubject.computeIfAbsent(m.getSubjectId(), k -> new EnumMap<>(Component.class)).put(m.getComponent(), v);
    }
    Map<Long, SubjectMarks> out = new LinkedHashMap<>();
    bySubject.forEach((sid, c) -> out.put(sid, calc(sid, c, r)));
    return out;
  }

  public static SubjectMarks calc(Long subjectId, Map<Component, Double> c, Rules r) {
    List<Double> ias = new ArrayList<>();
    for (Component k : List.of(Component.IA1, Component.IA2, Component.IA3)) if (c.get(k) != null) ias.add(c.get(k));
    ias.sort(Comparator.reverseOrder());
    double iaBucket = ias.stream().limit(r.iaBestCount()).mapToDouble(Double::doubleValue).sum();
    double iaConv = round(iaBucket * r.iaConvertedMax() / r.iaBucketMax());
    double actBucket = 0;
    for (Component k : List.of(Component.ACT1, Component.ACT2, Component.ACT3)) if (c.get(k) != null) actBucket += c.get(k);
    double actConv = round(actBucket * r.actConvertedMax() / r.actBucketMax());
    return new SubjectMarks(subjectId, c.get(Component.IA1), c.get(Component.IA2), c.get(Component.IA3),
        c.get(Component.ACT1), c.get(Component.ACT2), c.get(Component.ACT3), iaBucket, iaConv, actBucket, actConv, iaConv + actConv);
  }

  /** Half-up rounding to whole marks, matching the college ERP (8.5 -> 9). */
  public static double round(double v) { return BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_UP).doubleValue(); }
}
