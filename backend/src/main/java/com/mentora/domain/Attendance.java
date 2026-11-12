package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="attendance") @Getter @Setter @NoArgsConstructor
public class Attendance {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long studentId;
  private Long subjectId;
  @Column(name="att_date") private LocalDate date;
  @Enumerated(EnumType.STRING) private AttStatus status;
}
