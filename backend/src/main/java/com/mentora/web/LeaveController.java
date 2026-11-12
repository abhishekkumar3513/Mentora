package com.mentora.web;

import com.mentora.config.UserPrincipal;
import com.mentora.domain.LeaveDocument;
import com.mentora.domain.LeaveType;
import com.mentora.service.AccessService;
import com.mentora.service.FileStorageService;
import com.mentora.service.LeaveService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {
  public record ReviewRequest(boolean approve, @Size(max = 1000) String comment) {}

  private final LeaveService leaves;
  private final AccessService access;
  private final FileStorageService storage;

  public LeaveController(LeaveService leaves, AccessService access, FileStorageService storage) { this.leaves = leaves; this.access = access; this.storage = storage; }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public LeaveService.LeaveDto submit(@RequestParam LeaveType type,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                      @RequestParam String reason, @RequestParam(required = false) List<MultipartFile> files) {
    return leaves.submit(access.current(), type, startDate, endDate, reason, files);
  }

  @PutMapping("/{id}/review")
  public LeaveService.LeaveDto review(@PathVariable Long id, @Valid @RequestBody ReviewRequest r) {
    return leaves.review(access.current(), id, r.approve(), r.comment());
  }

  @GetMapping("/{leaveId}/documents/{docId}")
  public ResponseEntity<Resource> document(@PathVariable Long leaveId, @PathVariable Long docId, @RequestParam(defaultValue = "false") boolean download) {
    UserPrincipal p = access.current();
    LeaveDocument d = leaves.authorizedDocument(p, leaveId, docId);
    Resource res = storage.load(d.getStoragePath());
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.getContentType()))
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CONTENT_DISPOSITION, (download ? "attachment" : "inline") + "; filename=\"" + d.getFileName().replace("\"", "") + "\"")
        .body(res);
  }
}
