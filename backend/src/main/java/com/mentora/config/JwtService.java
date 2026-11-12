package com.mentora.config;

import com.mentora.domain.Role;
import com.mentora.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {
  private final SecretKey key;
  private final int expiryHours;

  public JwtService(AppProps props) {
    this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
    this.expiryHours = props.jwt().expiryHours();
  }

  public String generate(User u) {
    Instant now = Instant.now();
    return Jwts.builder().subject(String.valueOf(u.getId())).claim("email", u.getEmail()).claim("role", u.getRole().name())
        .issuedAt(Date.from(now)).expiration(Date.from(now.plus(expiryHours, ChronoUnit.HOURS))).signWith(key).compact();
  }

  public Optional<UserPrincipal> parse(String token) {
    try {
      Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      return Optional.of(new UserPrincipal(Long.valueOf(c.getSubject()), c.get("email", String.class), Role.valueOf(c.get("role", String.class))));
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
