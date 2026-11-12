package com.mentora.service;

import com.mentora.domain.Message;
import com.mentora.domain.ModerationLog;
import com.mentora.repo.MessageRepository;
import com.mentora.repo.ModerationLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic message moderation: profanity, links, length, rate limit, duplicates, temporary restriction. Never calls an LLM. */
@Service
public class ModerationService {
  public static final String GUIDELINES_MESSAGE =
      "Your message could not be sent because it violates the communication guidelines. Please maintain respectful communication.";

  public record Verdict(boolean allowed, String reason, String message) {
    static Verdict ok() { return new Verdict(true, null, null); }
  }

  private static final Set<String> PROFANITY = Set.of("fuck", "fucking", "fucker", "shit", "bitch", "bastard", "asshole", "dick", "cunt",
      "bullshit", "motherfucker", "slut", "whore", "idiot", "stupid", "moron", "dumb", "madarchod", "bhenchod", "chutiya", "bsdk", "harami");
  private static final Pattern URL = Pattern.compile("(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|xyz|ru|top|click|info|biz|tk|ml|ly|gl)\\b)", Pattern.CASE_INSENSITIVE);
  private static final Set<String> ALLOWED_DOMAINS = Set.of("rnsit.ac.in", "docs.google.com", "drive.google.com");

  private final ModerationLogRepository logs;
  private final MessageRepository messages;
  private final SettingsService settings;

  public ModerationService(ModerationLogRepository logs, MessageRepository messages, SettingsService settings) {
    this.logs = logs; this.messages = messages; this.settings = settings;
  }

  /** Lowercase, de-leet, collapse whitespace and repeated letters. */
  public static String normalize(String s) {
    if (s == null) return "";
    String t = s.toLowerCase().trim();
    t = t.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('@', 'a').replace('$', 's');
    t = t.replaceAll("(.)\\1{2,}", "$1$1").replaceAll("\\s+", " ");
    return t;
  }

  public static boolean containsProfanity(String normalized) {
    for (String w : normalized.split("[^a-z]+")) {
      if (PROFANITY.contains(w)) return true;
      String collapsed = w.replaceAll("(.)\\1+", "$1");   // fuuuck -> fuck
      if (PROFANITY.contains(collapsed)) return true;
    }
    return false;
  }

  public static boolean hasSuspiciousLink(String text) {
    var m = URL.matcher(text);
    while (m.find()) {
      String lower = text.toLowerCase();
      boolean allowed = ALLOWED_DOMAINS.stream().anyMatch(lower::contains);
      if (!allowed) return true;
    }
    return false;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Verdict check(Long userId, String content) {
    Instant now = Instant.now();
    int restrictMin = settings.integer("moderation.restrict_minutes", 5);
    // temporary restriction after repeated violations (restriction attempts themselves are not logged)
    long recent = logs.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofMinutes(restrictMin)));
    if (recent >= 3) {
      Instant last = logs.lastViolation(userId);
      long wait = Math.max(1, restrictMin - Duration.between(last, now).toMinutes());
      return new Verdict(false, "RESTRICTED", "You are temporarily restricted from sending messages because of repeated violations. Please try again in about " + wait + " minute(s).");
    }
    if (content == null || content.isBlank()) return new Verdict(false, "EMPTY", "Message cannot be empty.");
    int max = settings.integer("moderation.max_length", 1000);
    if (content.length() > max) return violation(userId, content, "TOO_LONG", "Your message is too long (maximum " + max + " characters).");
    String norm = normalize(content);
    if (containsProfanity(norm)) return violation(userId, content, "PROFANITY", GUIDELINES_MESSAGE);
    if (hasSuspiciousLink(content)) return violation(userId, content, "SUSPICIOUS_LINK", "Links are not allowed in messages. Please attach documents instead.");
    int rate = settings.integer("moderation.rate_per_minute", 8);
    if (messages.countBySenderIdAndCreatedAtAfter(userId, now.minus(Duration.ofMinutes(1))) >= rate)
      return violation(userId, content, "RATE_LIMIT", "You are sending messages too quickly. Please wait a moment.");
    List<Message> last = messages.findTop5BySenderIdOrderByCreatedAtDesc(userId);
    for (Message m : last) {
      if (m.getCreatedAt().isAfter(now.minus(Duration.ofMinutes(2))) && normalize(m.getContent()).equals(norm))
        return violation(userId, content, "DUPLICATE", "You already sent this message. Please avoid repeating the same message.");
    }
    return Verdict.ok();
  }

  private Verdict violation(Long userId, String content, String reason, String message) {
    ModerationLog l = new ModerationLog();
    l.setUserId(userId); l.setContent(content.length() > 500 ? content.substring(0, 500) : content); l.setReason(reason);
    logs.save(l);
    return new Verdict(false, reason, message);
  }
}
