package com.mentora.service;

import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.web.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validating CSV/XLSX importer for attendance, internal marks and semester results with a per-row report. */
@Service
public class ImportService {
  public record RowError(int row, String message) {}
  public record Report(String type, int total, int inserted, int updated, int duplicates, int failed, List<RowError> errors) {}

  private final StudentRepository students;
  private final SubjectRepository subjects;
  private final AttendanceRepository attendance;
  private final MarkRepository marks;
  private final SemesterResultRepository results;
  private final SettingsService settings;
  private final AttendanceAlertService alerts;
  private final AnalyticsService analytics;

  public ImportService(StudentRepository students, SubjectRepository subjects, AttendanceRepository attendance, MarkRepository marks,
                       SemesterResultRepository results, SettingsService settings, AttendanceAlertService alerts, AnalyticsService analytics) {
    this.students = students; this.subjects = subjects; this.attendance = attendance; this.marks = marks; this.results = results;
    this.settings = settings; this.alerts = alerts; this.analytics = analytics;
  }

  @Transactional
  public Report importFile(String type, MultipartFile file) {
    if (file == null || file.isEmpty()) throw ApiException.bad("Choose a CSV or XLSX file");
    List<Map<String, String>> rows = parse(file);
    if (rows.isEmpty()) throw ApiException.bad("The file has no data rows");
    if (rows.size() > 20000) throw ApiException.bad("File too large: maximum 20,000 rows per import");
    Map<String, Student> st = students.findAll().stream().collect(Collectors.toMap(s -> s.getUsn().toUpperCase(), Function.identity()));
    Map<String, Subject> sb = subjects.findAll().stream().collect(Collectors.toMap(s -> s.getCode().toUpperCase(), Function.identity()));
    int[] c = new int[4]; // inserted, updated, duplicates, failed
    List<RowError> errors = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Map<Long, LocalDate> attDates = new HashMap<>();
    Set<Long> markStudents = new HashSet<>();
    int n = 1;
    for (Map<String, String> r : rows) {
      n++;
      try {
        Student s = st.get(req(r, "usn").toUpperCase());
        if (s == null) throw new IllegalArgumentException("Unknown USN '" + r.get("usn") + "'");
        Subject subj = sb.get(req(r, "subjectcode").toUpperCase());
        if (subj == null) throw new IllegalArgumentException("Unknown subject code '" + r.get("subjectcode") + "'");
        switch (type) {
          case "attendance" -> {
            LocalDate d = date(req(r, "date"));
            if (d.isAfter(LocalDate.now())) throw new IllegalArgumentException("Attendance date is in the future");
            AttStatus status = parseEnum(AttStatus.class, req(r, "status"), "status (P, A, OD, L, NC)");
            if (!seen.add(s.getId() + "|" + subj.getId() + "|" + d)) { c[2]++; errors.add(new RowError(n, "Duplicate row in file (same student, subject and date)")); continue; }
            Optional<Attendance> ex = attendance.findByStudentIdAndSubjectIdAndDate(s.getId(), subj.getId(), d);
            Attendance a = ex.orElseGet(Attendance::new);
            a.setStudentId(s.getId()); a.setSubjectId(subj.getId()); a.setDate(d); a.setStatus(status);
            attendance.save(a);
            c[ex.isPresent() ? 1 : 0]++;
            attDates.merge(s.getId(), d, (x, y) -> x.isAfter(y) ? x : y);
          }
          case "marks" -> {
            Component comp = parseEnum(Component.class, req(r, "component"), "component (IA1, IA2, IA3, ACT1, ACT2, ACT3)");
            String raw = req(r, "score");
            boolean absent = raw.equalsIgnoreCase("AB") || raw.equalsIgnoreCase("absent");
            BigDecimal score = null;
            if (!absent) {
              try { score = new BigDecimal(raw); } catch (NumberFormatException e) { throw new IllegalArgumentException("Score '" + raw + "' is not a number"); }
              double max = comp.isIa() ? settings.num("ia.max_per_test", 25) : settings.num("activity.max_each", 10);
              if (score.signum() < 0 || score.doubleValue() > max) throw new IllegalArgumentException("Score must be between 0 and " + (int) max + " for " + comp);
            }
            if (!seen.add(s.getId() + "|" + subj.getId() + "|" + comp)) { c[2]++; errors.add(new RowError(n, "Duplicate row in file (same student, subject and component)")); continue; }
            Optional<Mark> ex = marks.findByStudentIdAndSubjectIdAndComponent(s.getId(), subj.getId(), comp);
            Mark m = ex.orElseGet(Mark::new);
            m.setStudentId(s.getId()); m.setSubjectId(subj.getId()); m.setComponent(comp); m.setScore(score); m.setAbsent(absent);
            marks.save(m);
            c[ex.isPresent() ? 1 : 0]++;
            markStudents.add(s.getId());
          }
          case "results" -> {
            String grade = req(r, "grade").toUpperCase();
            Integer gp = ResultsService.GRADE_POINTS.get(grade);
            if (gp == null) throw new IllegalArgumentException("Invalid grade '" + grade + "' (O, A+, A, B+, B, C, P, F)");
            if (!seen.add(s.getId() + "|" + subj.getId())) { c[2]++; errors.add(new RowError(n, "Duplicate row in file (same student and subject)")); continue; }
            Optional<SemesterResult> ex = results.findByStudentIdAndSubjectId(s.getId(), subj.getId());
            SemesterResult sr = ex.orElseGet(SemesterResult::new);
            sr.setStudentId(s.getId()); sr.setSubjectId(subj.getId()); sr.setSemester(subj.getSemester()); sr.setGrade(grade); sr.setGradePoints(gp); sr.setStatus(gp == 0 ? "FAIL" : "PASS");
            results.save(sr);
            c[ex.isPresent() ? 1 : 0]++;
          }
          default -> throw ApiException.bad("Unknown import type '" + type + "'");
        }
      } catch (IllegalArgumentException e) {
        c[3]++; errors.add(new RowError(n, e.getMessage()));
      }
    }
    attendance.flush();
    attDates.forEach((sid, d) -> alerts.evaluate(List.of(sid), d));
    if (!markStudents.isEmpty()) { marks.flush(); alerts.evaluateAcademic(analytics.metrics(markStudents)); }
    return new Report(type, rows.size(), c[0], c[1], c[2], c[3], errors.stream().limit(200).toList());
  }

  /** Records one day's status for every subject of the student's current semester and triggers mentor alerts. */
  @Transactional
  public int markDay(String usn, LocalDate date, AttStatus status) {
    Student s = students.findByUsnIgnoreCase(usn == null ? "" : usn.trim()).orElseThrow(() -> ApiException.bad("Unknown USN"));
    return markDayFor(s, date, status);
  }

  /** Same as markDay but for an already-authorized student (used by the mentor attendance editor). */
  @Transactional
  public int markDayFor(Student s, LocalDate date, AttStatus status) {
    if (date == null || date.isAfter(LocalDate.now())) throw ApiException.bad("Date must be today or earlier");
    List<Subject> subs = subjects.findBySemesterOrderByCode(s.getSemester());
    if (subs.isEmpty()) throw ApiException.bad("No subjects configured for semester " + s.getSemester());
    for (Subject sub : subs) {
      Attendance a = attendance.findByStudentIdAndSubjectIdAndDate(s.getId(), sub.getId(), date).orElseGet(Attendance::new);
      a.setStudentId(s.getId()); a.setSubjectId(sub.getId()); a.setDate(date); a.setStatus(status);
      attendance.save(a);
    }
    attendance.flush();
    return alerts.evaluate(List.of(s.getId()), date);
  }

  private static String req(Map<String, String> r, String key) {
    String v = r.get(key);
    if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing value for '" + key + "'");
    return v.trim();
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> cls, String v, String label) {
    try { return Enum.valueOf(cls, v.trim().toUpperCase()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid " + label + ": '" + v + "'"); }
  }

  private static LocalDate date(String v) {
    for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("dd/MM/yyyy"))) {
      try { return LocalDate.parse(v.trim(), f); } catch (DateTimeParseException ignored) { }
    }
    throw new IllegalArgumentException("Invalid date '" + v + "' (use yyyy-MM-dd or dd-MM-yyyy)");
  }

  // ----- parsing -----
  static List<Map<String, String>> parse(MultipartFile file) {
    String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
    try {
      if (name.endsWith(".xlsx")) return parseXlsx(file.getInputStream());
      if (name.endsWith(".csv")) return parseCsv(new String(file.getBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) { throw ApiException.bad("Could not read file"); }
    throw ApiException.bad("Only .csv and .xlsx files are supported");
  }

  static List<Map<String, String>> parseCsv(String text) {
    List<List<String>> lines = new ArrayList<>();
    for (String line : text.replace("﻿", "").split("\\r?\\n")) {
      if (line.isBlank()) continue;
      List<String> cells = new ArrayList<>();
      StringBuilder cur = new StringBuilder(); boolean q = false;
      for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i);
        if (ch == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = !q; }
        else if (ch == ',' && !q) { cells.add(cur.toString().trim()); cur.setLength(0); }
        else cur.append(ch);
      }
      cells.add(cur.toString().trim());
      lines.add(cells);
    }
    return toMaps(lines);
  }

  static List<Map<String, String>> parseXlsx(InputStream in) throws IOException {
    List<List<String>> lines = new ArrayList<>();
    try (Workbook wb = WorkbookFactory.create(in)) {
      Sheet sh = wb.getSheetAt(0);
      DataFormatter fmt = new DataFormatter();
      for (Row row : sh) {
        List<String> cells = new ArrayList<>();
        int last = Math.max(row.getLastCellNum(), 0);
        for (int i = 0; i < last; i++) {
          Cell cell = row.getCell(i);
          if (cell == null) { cells.add(""); continue; }
          if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) cells.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
          else cells.add(fmt.formatCellValue(cell).trim());
        }
        if (cells.stream().anyMatch(s -> !s.isEmpty())) lines.add(cells);
      }
    }
    return toMaps(lines);
  }

  private static List<Map<String, String>> toMaps(List<List<String>> lines) {
    if (lines.isEmpty()) return List.of();
    List<String> header = lines.get(0).stream().map(h -> h.toLowerCase().replaceAll("[^a-z0-9]", "")).toList();
    List<Map<String, String>> out = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      Map<String, String> m = new HashMap<>();
      for (int j = 0; j < header.size() && j < lines.get(i).size(); j++) m.put(header.get(j), lines.get(i).get(j));
      out.add(m);
    }
    return out;
  }
}
