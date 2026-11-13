package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="faculty") @Getter @Setter @NoArgsConstructor
public class Faculty {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long userId;
  private Long departmentId;
  private String employeeId;
  private String designation;
  @Column(name="is_counsellor") private boolean counsellor;
}
