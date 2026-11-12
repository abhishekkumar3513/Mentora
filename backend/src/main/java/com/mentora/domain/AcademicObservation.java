package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="academic_observations") @Getter @Setter @NoArgsConstructor
public class AcademicObservation {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long studentId;
  private Long facultyId;
  private String content;
  private Instant createdAt = Instant.now();
}
