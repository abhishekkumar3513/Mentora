package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="semester_results") @Getter @Setter @NoArgsConstructor
public class SemesterResult {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long studentId;
  private Long subjectId;
  private int semester;
  private String grade;
  private int gradePoints;
  private String status;
}
