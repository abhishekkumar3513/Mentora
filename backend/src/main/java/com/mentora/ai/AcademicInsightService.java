package com.mentora.ai;

import com.mentora.service.StudentDataService.MarksRow;
import java.util.*;

/** Generates plain-language academic insights strictly from stored data (no model involved, nothing predicted). */
public final class AcademicInsightService {
  private AcademicInsightService() {}

  public static List<String> insights(List<MarksRow> rows, List<Map<String, Object>> trend, List<Map<String, Object>> sgpa, double attendanceThreshold) {
    List<String> out = new ArrayList<>();
    for (MarksRow r : rows) {
      Double a = r.marks().ia1(), b = r.marks().ia2(), c = r.marks().ia3();
      if (a != null && b != null) {
        if (b > a) out.add("Your " + r.name() + " marks improved from IA 1 (" + fmt(a) + ") to IA 2 (" + fmt(b) + ").");
        else if (b < a) out.add("Your " + r.name() + " marks declined from IA 1 (" + fmt(a) + ") to IA 2 (" + fmt(b) + ").");
      }
      if (b != null && c != null) {
        if (c > b) out.add("Your " + r.name() + " marks improved from IA 2 (" + fmt(b) + ") to IA 3 (" + fmt(c) + ").");
        else if (c < b) out.add("Your " + r.name() + " marks declined from IA 2 (" + fmt(b) + ") to IA 3 (" + fmt(c) + ").");
      }
    }
    if (trend.size() >= 2) {
      double last = ((Number) trend.get(trend.size() - 1).get("percent")).doubleValue();
      double prev = ((Number) trend.get(trend.size() - 2).get("percent")).doubleValue();
      if (last < attendanceThreshold) out.add("Your attendance in the latest week (" + last + "%) is below the " + (int) attendanceThreshold + "% requirement.");
      else if (last > prev) out.add("Your weekly attendance rose from " + prev + "% to " + last + "%.");
      else if (last < prev) out.add("Your weekly attendance dropped from " + prev + "% to " + last + "%.");
    }
    if (sgpa.size() >= 2) {
      double last = ((Number) sgpa.get(sgpa.size() - 1).get("sgpa")).doubleValue();
      double prev = ((Number) sgpa.get(sgpa.size() - 2).get("sgpa")).doubleValue();
      if (last != prev) out.add("Your SGPA " + (last > prev ? "increased" : "decreased") + " from " + prev + " to " + last + " in the latest semester.");
    }
    if (out.isEmpty()) out.add("Not enough data yet to show a trend.");
    return out;
  }

  private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }
}
