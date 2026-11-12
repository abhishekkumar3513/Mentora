package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="users") @Getter @Setter @NoArgsConstructor
public class User {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(unique=true) private String email;
  private String passwordHash;
  private String fullName;
  @Enumerated(EnumType.STRING) private Role role;
  private String phone;
  private boolean enabled = true;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();
}
