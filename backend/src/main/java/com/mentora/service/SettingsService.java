package com.mentora.service;

import com.mentora.domain.Setting;
import com.mentora.repo.SettingRepository;
import com.mentora.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;

/** Central access to configurable thresholds and assessment rules (stored in PostgreSQL). */
@Service
public class SettingsService {
  private final SettingRepository repo;

  public SettingsService(SettingRepository repo) { this.repo = repo; }

  public Map<String, Setting> all() {
    Map<String, Setting> m = new LinkedHashMap<>();
    repo.findAll().stream().sorted((a, b) -> a.getKey().compareTo(b.getKey())).forEach(s -> m.put(s.getKey(), s));
    return m;
  }

  public String get(String key, String def) { return repo.findById(key).map(Setting::getValue).orElse(def); }
  public double num(String key, double def) {
    try { return Double.parseDouble(get(key, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
  }
  public int integer(String key, int def) { return (int) num(key, def); }
  public boolean bool(String key, boolean def) { return Boolean.parseBoolean(get(key, String.valueOf(def))); }

  @Transactional
  public Setting update(String key, String value) {
    Setting s = repo.findById(key).orElseThrow(() -> ApiException.notFound("Unknown setting " + key));
    if (value == null || value.isBlank()) throw ApiException.bad("Value required");
    if (!"attendance.leave_counts_as_present".equals(key)) {
      try {
        if (Double.parseDouble(value) < 0) throw ApiException.bad("Value must not be negative");
      } catch (NumberFormatException e) { throw ApiException.bad("Value must be numeric"); }
    } else if (!value.equals("true") && !value.equals("false")) {
      throw ApiException.bad("Value must be true or false");
    }
    s.setValue(value.trim());
    return repo.save(s);
  }
}
