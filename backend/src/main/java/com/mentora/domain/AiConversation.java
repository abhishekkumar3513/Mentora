package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="ai_conversations") @Getter @Setter @NoArgsConstructor
public class AiConversation {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long userId;
  private String title;
  private Instant createdAt = Instant.now();
}
