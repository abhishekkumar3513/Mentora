package com.mentora.web;

import com.mentora.ai.AIConversationService;
import com.mentora.ai.StudentMessageUnderstandingService;
import com.mentora.config.UserPrincipal;
import com.mentora.domain.Message;
import com.mentora.service.AccessService;
import com.mentora.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
class NotificationController {
  private final NotificationService notifications;
  private final AccessService access;

  NotificationController(NotificationService notifications, AccessService access) { this.notifications = notifications; this.access = access; }

  @GetMapping
  public List<NotificationService.NotificationDto> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) { return notifications.list(access.current().id(), page, size); }

  @GetMapping("/unread-count")
  public Map<String, Object> unread() { return Map.of("count", notifications.unread(access.current().id())); }

  @PostMapping("/{id}/read")
  public Map<String, Object> read(@PathVariable Long id) { notifications.markRead(access.current().id(), id); return Map.of("ok", true); }

  @PostMapping("/read-all")
  public Map<String, Object> readAll() { notifications.markAllRead(access.current().id()); return Map.of("ok", true); }
}

/** Gemini-backed endpoints (mentor only, enforced in SecurityConfig and again in the services). */
@RestController
@RequestMapping("/api/ai")
class AiController {
  public record AskRequest(Long conversationId, @NotBlank String question) {}

  private final AIConversationService conversations;
  private final StudentMessageUnderstandingService understanding;
  private final AccessService access;

  AiController(AIConversationService conversations, StudentMessageUnderstandingService understanding, AccessService access) {
    this.conversations = conversations; this.understanding = understanding; this.access = access;
  }

  @PostMapping("/assistant")
  public AIConversationService.AskResult ask(@Valid @RequestBody AskRequest r) { return conversations.ask(access.current(), r.conversationId(), r.question()); }

  @GetMapping("/conversations")
  public List<AIConversationService.ConversationDto> list() { return conversations.list(access.current()); }

  @GetMapping("/conversations/{id}")
  public List<AIConversationService.MessageDto> messages(@PathVariable Long id) { return conversations.messages(access.current(), id); }

  /** Regenerate the AI summary of a student message (mentor must be assigned to that student). */
  @PostMapping("/messages/{id}/summary")
  public Map<String, Object> summarize(@PathVariable Long id) {
    UserPrincipal p = access.current();
    Message m = understanding.regenerate(p, id);
    return Map.of("messageId", m.getId(), "aiStatus", String.valueOf(m.getAiStatus()));
  }
}
