package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="departments") @Getter @Setter @NoArgsConstructor
public class Department {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private String code;
  private String name;
}
