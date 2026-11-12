package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="marks") @Getter @Setter @NoArgsConstructor
public class Mark {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long studentId;
  private Long subjectId;
  @Enumerated(EnumType.STRING) private Component component;
  private BigDecimal score;
  private boolean absent;
}
