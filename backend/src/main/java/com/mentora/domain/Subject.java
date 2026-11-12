package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="subjects") @Getter @Setter @NoArgsConstructor
public class Subject {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private String code;
  private String name;
  private int credits;
  private int semester;
  private Long departmentId;
}
