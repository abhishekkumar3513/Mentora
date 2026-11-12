package com.mentora.web;

import com.mentora.config.JwtService;
import com.mentora.config.UserPrincipal;
import com.mentora.domain.*;
import com.mentora.repo.*;
import com.mentora.service.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
  public record ProfileRequest(@NotBlank @Size(max = 150) String fullName, @Size(max = 20) String phone) {}
  public record PasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 100) String newPassword) {}

  private final UserRepository users;
  private final StudentRepository students;
  private final FacultyRepository faculty;
  private final PasswordEncoder encoder;
  private final JwtService jwt;
  private final AccessService access;
  private final Map<String, Deque<Long>> attempts = new HashMap<>();

  public AuthController(UserRepository users, StudentRepository students, FacultyRepository faculty, PasswordEncoder encoder, JwtService jwt, AccessService access) {
    this.users = users; this.students = students; this.faculty = faculty; this.encoder = encoder; this.jwt = jwt; this.access = access;
  }

  /** Simple in-memory brute-force protection: 10 failed attempts per minute per IP+email. */
  private synchronized void checkRate(String key, boolean failed) {
    long now = System.currentTimeMillis();
    Deque<Long> d = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
    while (!d.isEmpty() && now - d.peekFirst() > 60_000) d.pollFirst();
    if (failed) d.addLast(now);
    else if (d.size() >= 10) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Try again in a minute.");
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
    String key = req.getRemoteAddr() + "|" + r.email().toLowerCase();
    checkRate(key, false);
    User u = users.findByEmailIgnoreCase(r.email().trim()).orElse(null);
    if (u == null || !u.isEnabled() || !encoder.matches(r.password(), u.getPasswordHash())) {
      checkRate(key, true);
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("token", jwt.generate(u));
    out.put("user", userView(u));
    return out;
  }

  /** Stateless JWT: logout is handled by the client discarding the token; endpoint kept for API symmetry. */
  @PostMapping("/logout")
  public Map<String, Object> logout() { return Map.of("ok", true); }

  @GetMapping("/me")
  public Map<String, Object> me() {
    UserPrincipal p = access.current();
    return userView(users.findById(p.id()).orElseThrow());
  }

  @PutMapping("/profile")
  @Transactional
  public Map<String, Object> profile(@Valid @RequestBody ProfileRequest r) {
    User u = users.findById(access.current().id()).orElseThrow();
    u.setFullName(r.fullName().trim()); u.setPhone(r.phone()); u.setUpdatedAt(Instant.now());
    return userView(u);
  }

  @PostMapping("/password")
  @Transactional
  public Map<String, Object> password(@Valid @RequestBody PasswordRequest r) {
    User u = users.findById(access.current().id()).orElseThrow();
    if (!encoder.matches(r.currentPassword(), u.getPasswordHash())) throw ApiException.bad("Current password is incorrect");
    u.setPasswordHash(encoder.encode(r.newPassword())); u.setUpdatedAt(Instant.now());
    return Map.of("ok", true);
  }

  private Map<String, Object> userView(User u) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", u.getId()); m.put("email", u.getEmail()); m.put("fullName", u.getFullName()); m.put("role", u.getRole()); m.put("phone", u.getPhone());
    if (u.getRole() == Role.STUDENT) students.findByUserId(u.getId()).ifPresent(s -> { m.put("studentId", s.getId()); m.put("usn", s.getUsn()); });
    if (u.getRole() == Role.MENTOR) faculty.findByUserId(u.getId()).ifPresent(f -> { m.put("facultyId", f.getId()); m.put("employeeId", f.getEmployeeId()); });
    return m;
  }
}
