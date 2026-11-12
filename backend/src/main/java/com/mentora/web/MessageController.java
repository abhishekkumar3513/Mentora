package com.mentora.web;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.Message;
import com.mentora.service.AccessService;
import com.mentora.service.FileStorageService;
import com.mentora.service.MessageService;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
class MessageRestController {
  private final MessageService messages;
  private final AccessService access;
  private final FileStorageService storage;

  MessageRestController(MessageService messages, AccessService access, FileStorageService storage) { this.messages = messages; this.access = access; this.storage = storage; }

  @GetMapping("/{studentId}")
  public List<MessageService.MessageDto> history(@PathVariable Long studentId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return messages.history(access.current(), studentId, page, size);
  }

  /** REST send (used for attachments and as a fallback when the socket is down). Responses carry the blocked reason on 422. */
  @PostMapping(value = "/{studentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public MessageService.MessageDto send(@PathVariable Long studentId, @RequestParam(required = false) String content, @RequestParam(required = false) MultipartFile file) {
    return messages.send(access.current(), studentId, content, file);
  }

  @PostMapping("/{studentId}/read")
  public Map<String, Object> read(@PathVariable Long studentId) { messages.markRead(access.current(), studentId); return Map.of("ok", true); }

  @GetMapping("/unread-count")
  public Map<String, Object> unread() { return Map.of("count", messages.unreadCount(access.current())); }

  @GetMapping("/attachments/{messageId}")
  public ResponseEntity<Resource> attachment(@PathVariable Long messageId) {
    Message m = messages.authorizedAttachment(access.current(), messageId);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(m.getAttachmentType())).header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + m.getAttachmentName().replace("\"", "") + "\"").body(storage.load(m.getAttachmentPath()));
  }
}

/** STOMP entry point: clients send to /app/chat.send and listen on /user/queue/messages, /queue/errors, /queue/read, /queue/notifications, /queue/ai-summary. */
@Controller
class ChatSocketController {
  private static final Logger log = LoggerFactory.getLogger(ChatSocketController.class);
  public record SendPayload(@NotNull Long studentId, String content) {}
  public record ErrorPayload(String error, Long studentId) {}

  private final MessageService messages;
  private final SimpMessagingTemplate ws;

  ChatSocketController(MessageService messages, SimpMessagingTemplate ws) { this.messages = messages; this.ws = ws; }

  @MessageMapping("/chat.send")
  public void send(@Payload SendPayload payload, Principal principal) {
    UserPrincipal p = principalOf(principal);
    try {
      messages.send(p, payload.studentId(), payload.content(), null);
    } catch (ApiException e) {
      ws.convertAndSendToUser(principal.getName(), "/queue/errors", new ErrorPayload(e.getMessage(), payload.studentId()));
    } catch (Exception e) {
      log.warn("Socket send failed: {}", e.toString());
      ws.convertAndSendToUser(principal.getName(), "/queue/errors", new ErrorPayload("Message could not be sent", payload.studentId()));
    }
  }

  @MessageMapping("/chat.read")
  public void read(@Payload Map<String, Object> payload, Principal principal) {
    try { messages.markRead(principalOf(principal), ((Number) payload.get("studentId")).longValue()); } catch (Exception e) { log.debug("read failed: {}", e.toString()); }
  }

  private static UserPrincipal principalOf(Principal principal) {
    if (principal instanceof UsernamePasswordAuthenticationToken t && t.getPrincipal() instanceof UserPrincipal up) return up;
    throw ApiException.forbidden("Unauthorized");
  }
}
