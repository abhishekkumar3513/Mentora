package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="leave_audit") @Getter @Setter @NoArgsConstructor
public class LeaveAudit {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long leaveId;
  private String action;
  private Long actorId;
  private String comment;
  private Instant createdAt = Instant.now();
}
