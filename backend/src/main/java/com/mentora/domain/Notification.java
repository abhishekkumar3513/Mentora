package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Entity @Table(name="notifications") @Getter @Setter @NoArgsConstructor
public class Notification {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  private Long userId;
  @Enumerated(EnumType.STRING) private NotificationType type;
  private String title;
  private String description;
  private String link;
  @Column(name="is_read") private boolean read;
  private String dedupeKey;
  private String priority = "NORMAL";
  private Long studentId;
  private Instant createdAt = Instant.now();
}
