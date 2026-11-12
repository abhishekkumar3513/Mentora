package com.mentora.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> api(ApiException e) { return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage())); }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied")); }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
    String msg = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
    return ResponseEntity.badRequest().body(Map.of("error", msg));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Map<String, Object>> constraint(ConstraintViolationException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
    log.warn("Integrity violation: {}", e.getMostSpecificCause().getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Duplicate or invalid reference: the record conflicts with existing data"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, Object>> tooBig(MaxUploadSizeExceededException e) { return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "File too large (max 5 MB)")); }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> other(Exception e) {
    log.error("Unhandled error", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Unexpected server error"));
  }
}
