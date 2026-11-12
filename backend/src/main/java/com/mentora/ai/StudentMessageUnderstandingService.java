package com.mentora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mentora.config.UserPrincipal;
import com.mentora.domain.Faculty;
import com.mentora.domain.Message;
import com.mentora.repo.MessageRepository;
import com.mentora.service.AccessService;
import com.mentora.service.MessageService;
import com.mentora.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/** Generates a clearly-labelled, mentor-only summary of a student's message. It never decides leave outcomes. */
@Service
public class StudentMessageUnderstandingService {
  private static final Logger log = LoggerFactory.getLogger(StudentMessageUnderstandingService.class);
  private static final Set<String> CATEGORIES = Set.of("MEDICAL", "PERSONAL", "ACADEMIC", "ATTENDANCE", "LEAVE", "OTHER");
  private static final Set<String> URGENCY = Set.of("LOW", "MEDIUM", "HIGH");
  static final String SYSTEM = """
      You help a college faculty mentor quickly understand a message written by one of their students.
      Rules: use ONLY facts stated in the message. Never invent dates, reasons, documents or requests. If something is not stated, write "Unspecified".
      You must NOT approve, reject or recommend approving/rejecting any leave; you may only suggest that the mentor reviews or follows up.
      Do NOT diagnose, label or add any health condition, emotion or reason the student did not literally write (for example never say "depression", "anxiety" or "stress" unless those words are in the message).
      Quote the student's own wording for "reason". A date counts as stated only if the student wrote one ("yesterday", "on Monday", "12 Nov"); a vague message with no date must have date "Unspecified".
      Use urgency HIGH only if the student explicitly mentions an emergency, self-harm, hospitalisation or a serious illness; a plain "not feeling well" is LOW or MEDIUM.
      If the message is only a greeting or very short, say so in the summary and keep every other field "Unspecified".
      The suggested action should be a neutral follow-up (for example "Reply to the student and ask what support they need"), never a conclusion.
      Respond with a single JSON object with exactly these string fields:
      summary (1-2 sentences), reason, date, document, request, suggestedAction, category (one of MEDICAL, PERSONAL, ACADEMIC, ATTENDANCE, LEAVE, OTHER), urgency (one of LOW, MEDIUM, HIGH).
      For "document" say whether the student mentions or attaches one (attachment info is provided separately).""";

  private final GeminiService gemini;
  private final MessageRepository messages;
  private final ObjectMapper mapper;
  private final AccessService access;
  private final SimpMessagingTemplate ws;

  public StudentMessageUnderstandingService(GeminiService gemini, MessageRepository messages, ObjectMapper mapper, AccessService access, SimpMessagingTemplate ws) {
    this.gemini = gemini; this.messages = messages; this.mapper = mapper; this.access = access; this.ws = ws;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMessageSaved(MessageService.MessageSaved e) {
    try { summarizeInternal(e.messageId()); } catch (Exception ex) { log.warn("AI summary failed for message {}: {}", e.messageId(), ex.toString()); }
  }

  /** On-demand (re)generation by an authorized mentor. */
  public Message regenerate(UserPrincipal p, Long messageId) {
    Message m = messages.findById(messageId).orElseThrow(() -> ApiException.notFound("Message not found"));
    access.requireStudentAccess(p, m.getStudentId());
    Faculty f = access.facultyOf(p);
    if (m.getSenderId().equals(p.id())) throw ApiException.bad("Only student messages are summarized");
    summarizeInternal(messageId);
    return messages.findById(messageId).orElseThrow();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void summarizeInternal(Long messageId) {
    Message m = messages.findById(messageId).orElse(null);
    if (m == null || m.getAiStatus() == null) return;
    Optional<String> raw = gemini.isConfigured() ? gemini.generateJson(SYSTEM, buildPrompt(m)) : Optional.empty();
    if (!gemini.isConfigured()) { m.setAiStatus("UNAVAILABLE"); }
    else {
      Optional<ObjectNode> valid = raw.flatMap(this::validate);
      if (valid.isPresent()) { m.setAiSummary(valid.get().toString()); m.setAiStatus("READY"); }
      else m.setAiStatus("FAILED");
    }
    messages.save(m);
    Faculty mentor = access.mentorOf(m.getStudentId());
    if (mentor != null) {
      try {
        ws.convertAndSendToUser(String.valueOf(mentor.getUserId()), "/queue/ai-summary",
            java.util.Map.of("messageId", m.getId(), "studentId", m.getStudentId(), "aiStatus", m.getAiStatus(),
                "aiSummary", m.getAiSummary() == null ? mapper.nullNode() : mapper.readTree(m.getAiSummary())));
      } catch (Exception ignored) { }
    }
  }

  private String buildPrompt(Message m) {
    return "Today's date: " + LocalDate.now() + "\nAttachment: " + (m.getAttachmentName() == null ? "none" : "yes (" + m.getAttachmentName() + ")")
        + "\nStudent message:\n\"\"\"\n" + m.getContent() + "\n\"\"\"";
  }

  /** Validates the structured output; returns normalized JSON or empty if unusable. */
  Optional<ObjectNode> validate(String raw) {
    try {
      String t = raw.trim();
      if (t.startsWith("```")) t = t.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
      JsonNode n = mapper.readTree(t);
      if (!n.isObject() || !n.path("summary").isTextual() || n.path("summary").asText().isBlank()) return Optional.empty();
      ObjectNode out = mapper.createObjectNode();
      for (String f : new String[]{"summary", "reason", "date", "document", "request", "suggestedAction"}) {
        String v = n.path(f).isTextual() ? n.path(f).asText().trim() : "Unspecified";
        out.put(f, v.isEmpty() ? "Unspecified" : v.length() > 400 ? v.substring(0, 400) : v);
      }
      String cat = n.path("category").asText("OTHER").toUpperCase();
      String urg = n.path("urgency").asText("LOW").toUpperCase();
      out.put("category", CATEGORIES.contains(cat) ? cat : "OTHER");
      out.put("urgency", URGENCY.contains(urg) ? urg : "LOW");
      return Optional.of(out);
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
