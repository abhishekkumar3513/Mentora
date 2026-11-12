package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="leave_documents") @Getter @Setter @NoArgsConstructor
public class LeaveDocument {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long leaveId;
  private String fileName;
  private String contentType;
  @Column(name="size_bytes") private long size;
  private String storagePath;
  private Instant uploadedAt = Instant.now();
}
