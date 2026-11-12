package com.mentora.config;

import com.mentora.domain.Role;
import java.security.Principal;

public record UserPrincipal(Long id, String email, Role role) implements Principal {
  @Override public String getName() { return String.valueOf(id); }
  @Override public String toString() { return String.valueOf(id); }
}
