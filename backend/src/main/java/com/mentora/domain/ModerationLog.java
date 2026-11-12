package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="message_moderation_logs") @Getter @Setter @NoArgsConstructor
public class ModerationLog {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long userId;
  private String content;
  private String reason;
  private Instant createdAt = Instant.now();
}
