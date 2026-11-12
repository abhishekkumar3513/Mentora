package com.mentora.service;

import com.mentora.domain.Subject;
import com.mentora.domain.SemesterResult;
import com.mentora.repo.SemesterResultRepository;
import com.mentora.repo.SubjectRepository;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ResultsService {
  public record ResultRow(Long subjectId, String code, String name, int credits, String grade, int gradePoints, String status) {}
  public record SemesterBlock(int semester, List<ResultRow> rows, double sgpa, int credits) {}
  public record ResultsView(List<SemesterBlock> semesters, double cgpa) {}

  public static final Map<String, Integer> GRADE_POINTS = Map.of("O", 10, "A+", 9, "A", 8, "B+", 7, "B", 6, "C", 5, "P", 4, "F", 0);

  private final SemesterResultRepository results;
  private final SubjectRepository subjects;

  public ResultsService(SemesterResultRepository results, SubjectRepository subjects) { this.results = results; this.subjects = subjects; }

  public ResultsView forStudent(Long studentId) {
    return build(results.findByStudentIdOrderBySemester(studentId), subjectMap());
  }

  public Map<Long, Subject> subjectMap() { return subjects.findAll().stream().collect(Collectors.toMap(Subject::getId, Function.identity())); }

  public static ResultsView build(List<SemesterResult> rows, Map<Long, Subject> subj) {
    Map<Integer, List<SemesterResult>> bySem = new TreeMap<>();
    for (SemesterResult r : rows) bySem.computeIfAbsent(r.getSemester(), k -> new ArrayList<>()).add(r);
    List<SemesterBlock> blocks = new ArrayList<>();
    double totalPts = 0; int totalCredits = 0;
    for (var e : bySem.entrySet()) {
      List<ResultRow> rr = new ArrayList<>();
      double pts = 0; int cr = 0;
      for (SemesterResult r : e.getValue()) {
        Subject s = subj.get(r.getSubjectId());
        if (s == null) continue;
        rr.add(new ResultRow(s.getId(), s.getCode(), s.getName(), s.getCredits(), r.getGrade(), r.getGradePoints(), r.getStatus()));
        pts += (double) s.getCredits() * r.getGradePoints(); cr += s.getCredits();
      }
      rr.sort(Comparator.comparing(ResultRow::code));
      blocks.add(new SemesterBlock(e.getKey(), rr, cr == 0 ? 0 : round2(pts / cr), cr));
      totalPts += pts; totalCredits += cr;
    }
    return new ResultsView(blocks, totalCredits == 0 ? 0 : round2(totalPts / totalCredits));
  }

  public static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
