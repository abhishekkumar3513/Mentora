package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="students") @Getter @Setter @NoArgsConstructor
public class Student {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long userId;
  private Long departmentId;
  private String usn;
  private int semester;
  private String section;
  private Instant createdAt = Instant.now();
}
