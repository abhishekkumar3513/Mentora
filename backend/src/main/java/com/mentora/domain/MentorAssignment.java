package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="mentor_assignments") @Getter @Setter @NoArgsConstructor
public class MentorAssignment {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long studentId;
  private Long facultyId;
  private boolean active = true;
  private Instant assignedAt = Instant.now();
}
