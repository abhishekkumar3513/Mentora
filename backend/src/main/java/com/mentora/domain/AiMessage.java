package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="ai_messages") @Getter @Setter @NoArgsConstructor
public class AiMessage {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long conversationId;
  private String role;
  private String content;
  private String payload;
  private Instant createdAt = Instant.now();
}
