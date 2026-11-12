package com.mentora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mentora")
public record AppProps(Jwt jwt, String corsOrigins, String storageDir, boolean seedDemoData, Gemini gemini) {
  public record Jwt(String secret, int expiryHours) {}
  public record Gemini(String apiKey, String model, int timeoutSeconds) {}
}
