package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="semesters") @Getter @Setter @NoArgsConstructor
public class Semester {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="sem_number") private int number;
  private String academicYear;
  @Column(name="is_current") private boolean current;
}
