package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="mentor_feedback") @Getter @Setter @NoArgsConstructor
public class MentorFeedback {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long studentId;
  private Long facultyId;
  private String content;
  private Instant createdAt = Instant.now();
}
