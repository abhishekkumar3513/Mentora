package com.mentora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentora.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thin Gemini REST client. The API key stays on the server; every failure degrades to Optional.empty(). */
@Service
public class GeminiService {
  private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
  private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

  private final AppProps.Gemini cfg;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public GeminiService(AppProps props, ObjectMapper mapper) {
    this.cfg = props.gemini();
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public boolean isConfigured() { return cfg.apiKey() != null && !cfg.apiKey().isBlank(); }

  public Optional<String> generateText(String system, String prompt) { return call(system, prompt, false); }

  /** Requests a JSON response and returns the raw JSON text (caller validates). */
  public Optional<String> generateJson(String system, String prompt) { return call(system, prompt, true); }

  private Optional<String> call(String system, String prompt, boolean json) {
    if (!isConfigured()) return Optional.empty();
    try {
      Map<String, Object> gen = json ? Map.of("temperature", 0.2, "responseMimeType", "application/json") : Map.of("temperature", 0.3);
      String body = mapper.writeValueAsString(Map.of(
          "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
          "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
          "generationConfig", gen));
      // GEMINI_MODEL may list several models (comma separated); the next one is tried when a model is
      // retired (404), rate limited (429), overloaded (5xx) or times out.
      for (String model : cfg.model().split(",")) {
        model = model.trim();
        if (model.isEmpty()) continue;
        try {
          HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + model + ":generateContent"))
              .timeout(Duration.ofSeconds(cfg.timeoutSeconds())).header("Content-Type", "application/json").header("x-goog-api-key", cfg.apiKey())
              .POST(HttpRequest.BodyPublishers.ofString(body)).build();
          HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
          int code = res.statusCode();
          if (code == 404 || code == 429 || code >= 500) { log.warn("Gemini model {} returned {}: {}", model, code, abbreviate(res.body())); continue; }
          if (code != 200) { log.warn("Gemini error {} for model {}: {}", code, model, abbreviate(res.body())); return Optional.empty(); }
          JsonNode text = mapper.readTree(res.body()).path("candidates").path(0).path("content").path("parts").path(0).path("text");
          if (text.isTextual() && !text.asText().isBlank()) return Optional.of(text.asText());
          log.warn("Gemini model {} returned no text", model);
        } catch (java.net.http.HttpTimeoutException e) {
          log.warn("Gemini model {} timed out", model);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("Gemini call failed: {}", e.toString());
    }
    return Optional.empty();
  }

  private static String abbreviate(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s; }
}
