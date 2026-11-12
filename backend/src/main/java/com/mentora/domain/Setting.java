package com.mentora.domain;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="settings") @Getter @Setter @NoArgsConstructor
public class Setting {
  @Id @Column(name="setting_key") private String key;
  @Column(name="setting_value") private String value;
  private String description;
}
