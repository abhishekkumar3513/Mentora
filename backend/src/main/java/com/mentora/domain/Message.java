package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="messages") @Getter @Setter @NoArgsConstructor
public class Message {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long studentId;
  private Long senderId;
  private String content;
  private String attachmentName;
  private String attachmentPath;
  private String attachmentType;
  private Instant createdAt = Instant.now();
  private Instant readAt;
  private String aiSummary;
  private String aiStatus;
}
