package com.mentora.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Admin configuration of support categories (default action owner / active) and counsellor authorisation. Secured by /api/admin/** = ADMIN. */
@RestController
@RequestMapping("/api/admin")
public class AdminSupportController {
  public record CategoryRequest(String defaultOwner, Boolean active) {}
  public record CounsellorRequest(boolean counsellor) {}
  private final JdbcTemplate jdbc;
  public AdminSupportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @GetMapping("/support-categories")
  public List<Map<String, Object>> categories() {
    return jdbc.queryForList("select code, name, default_owner as \"defaultOwner\", confidential, active from support_categories order by sort_order");
  }

  @PutMapping("/support-categories/{code}")
  public Map<String, Object> updateCategory(@PathVariable String code, @RequestBody CategoryRequest r) {
    if (r.defaultOwner() != null && !com.mentora.service.SupportService.OWNER_ROLES.contains(r.defaultOwner())) throw ApiException.bad("Unknown action owner");
    int n = jdbc.update("update support_categories set default_owner = coalesce(?, default_owner), active = coalesce(?, active) where code = ?", r.defaultOwner(), r.active(), code);
    if (n == 0) throw ApiException.notFound("Category not found");
    return Map.of("ok", true);
  }

  @GetMapping("/counsellors")
  public List<Map<String, Object>> counsellors() {
    return jdbc.queryForList("select f.id as \"facultyId\", u.full_name as name, f.is_counsellor as counsellor from faculty f join users u on u.id = f.user_id order by u.full_name");
  }

  @PutMapping("/counsellors/{facultyId}")
  public Map<String, Object> setCounsellor(@PathVariable Long facultyId, @RequestBody CounsellorRequest r) {
    if (jdbc.update("update faculty set is_counsellor = ? where id = ?", r.counsellor(), facultyId) == 0) throw ApiException.notFound("Faculty not found");
    return Map.of("ok", true);
  }
}
