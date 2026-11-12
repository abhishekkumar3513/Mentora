package com.mentora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentora.config.UserPrincipal;
import com.mentora.domain.AiConversation;
import com.mentora.domain.AiMessage;
import com.mentora.repo.AiConversationRepository;
import com.mentora.repo.AiMessageRepository;
import com.mentora.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Persists AI assistant conversations and applies a per-mentor request rate limit. */
@Service
public class AIConversationService {
  public record ConversationDto(Long id, String title, Instant createdAt) {}
  public record MessageDto(Long id, String role, String content, JsonNode payload, Instant createdAt) {}
  public record AskResult(Long conversationId, MessageDto user, MessageDto assistant) {}

  private static final int MAX_PER_MINUTE = 15;
  private final Map<Long, Deque<Long>> recent = new HashMap<>();

  private final AiConversationRepository conversations;
  private final AiMessageRepository messages;
  private final MentorAnalyticsAssistantService assistant;
  private final ObjectMapper mapper;

  public AIConversationService(AiConversationRepository conversations, AiMessageRepository messages, MentorAnalyticsAssistantService assistant, ObjectMapper mapper) {
    this.conversations = conversations; this.messages = messages; this.assistant = assistant; this.mapper = mapper;
  }

  private synchronized void rateLimit(Long userId) {
    long now = System.currentTimeMillis();
    Deque<Long> d = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
    while (!d.isEmpty() && now - d.peekFirst() > 60_000) d.pollFirst();
    if (d.size() >= MAX_PER_MINUTE) throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too many assistant requests. Please wait a moment.");
    d.addLast(now);
  }

  @Transactional
  public AskResult ask(UserPrincipal p, Long conversationId, String question) {
    if (question == null || question.isBlank()) throw ApiException.bad("Question is required");
    if (question.length() > 500) throw ApiException.bad("Question is too long (max 500 characters)");
    rateLimit(p.id());
    AiConversation c;
    if (conversationId == null) {
      c = new AiConversation();
      c.setUserId(p.id()); c.setTitle(question.length() > 60 ? question.substring(0, 57) + "..." : question);
      conversations.save(c);
    } else {
      c = conversations.findById(conversationId).orElseThrow(() -> ApiException.notFound("Conversation not found"));
      if (!c.getUserId().equals(p.id())) throw ApiException.forbidden("Access denied");
    }
    AiMessage um = save(c.getId(), "USER", question.trim(), null);
    var reply = assistant.ask(p, question.trim());
    AiMessage am;
    try { am = save(c.getId(), "ASSISTANT", reply.answer(), mapper.writeValueAsString(reply)); }
    catch (Exception e) { throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not store reply"); }
    return new AskResult(c.getId(), dto(um), dto(am));
  }

  private AiMessage save(Long cid, String role, String content, String payload) {
    AiMessage m = new AiMessage();
    m.setConversationId(cid); m.setRole(role); m.setContent(content); m.setPayload(payload);
    return messages.save(m);
  }

  public List<ConversationDto> list(UserPrincipal p) {
    return conversations.findByUserIdOrderByCreatedAtDesc(p.id()).stream().limit(30).map(c -> new ConversationDto(c.getId(), c.getTitle(), c.getCreatedAt())).toList();
  }

  public List<MessageDto> messages(UserPrincipal p, Long id) {
    AiConversation c = conversations.findById(id).orElseThrow(() -> ApiException.notFound("Conversation not found"));
    if (!c.getUserId().equals(p.id())) throw ApiException.forbidden("Access denied");
    return messages.findByConversationIdOrderById(id).stream().map(this::dto).toList();
  }

  private MessageDto dto(AiMessage m) {
    JsonNode payload = null;
    try { if (m.getPayload() != null) payload = mapper.readTree(m.getPayload()); } catch (Exception ignored) { }
    return new MessageDto(m.getId(), m.getRole(), m.getContent(), payload, m.getCreatedAt());
  }
}
