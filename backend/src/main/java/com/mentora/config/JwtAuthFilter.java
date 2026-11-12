package com.mentora.config;

import com.mentora.repo.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Service
public class JwtAuthFilter extends OncePerRequestFilter {
  private final JwtService jwt;
  private final UserRepository users;

  public JwtAuthFilter(JwtService jwt, UserRepository users) { this.jwt = jwt; this.users = users; }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String header = req.getHeader("Authorization");
    String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    if (token != null) {
      jwt.parse(token).filter(p -> users.findById(p.id()).map(u -> u.isEnabled()).orElse(false)).ifPresent(p ->
          SecurityContextHolder.getContext().setAuthentication(
              new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name())))));
    }
    chain.doFilter(req, res);
  }
}
