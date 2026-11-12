package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="leave_requests") @Getter @Setter @NoArgsConstructor
public class LeaveRequest {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long studentId;
  @Enumerated(EnumType.STRING) @Column(name="leave_type") private LeaveType type;
  private LocalDate startDate;
  private LocalDate endDate;
  private String reason;
  @Enumerated(EnumType.STRING) private LeaveStatus status = LeaveStatus.PENDING;
  private String reviewComment;
  private Long reviewedBy;
  private Instant createdAt = Instant.now();
  private Instant reviewedAt;
}
