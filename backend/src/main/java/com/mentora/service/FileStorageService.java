package com.mentora.service;

import com.mentora.config.AppProps;
import com.mentora.web.ApiException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/** Stores uploaded documents on disk (metadata lives in PostgreSQL). Validates size, MIME type and magic bytes. */
@Service
public class FileStorageService {
  public static final long MAX_BYTES = 5L * 1024 * 1024;
  private static final Map<String, String> EXT = Map.of("application/pdf", "pdf", "image/jpeg", "jpg", "image/png", "png");
  private final Path root;

  public FileStorageService(AppProps props) { this.root = Path.of(props.storageDir()).toAbsolutePath().normalize(); }

  public record Stored(String path, String originalName, String contentType, long size) {}

  public Stored store(String folder, MultipartFile file) {
    if (file == null || file.isEmpty()) throw ApiException.bad("Empty file");
    if (file.getSize() > MAX_BYTES) throw ApiException.bad("File exceeds the 5 MB limit: " + file.getOriginalFilename());
    String ct = file.getContentType();
    if (ct == null || !EXT.containsKey(ct)) throw ApiException.bad("Only PDF, JPG and PNG files are allowed");
    try {
      byte[] head;
      try (InputStream in = file.getInputStream()) { head = in.readNBytes(8); }
      if (!magicMatches(ct, head)) throw ApiException.bad("File content does not match its type: " + file.getOriginalFilename());
      Path dir = root.resolve(folder).normalize();
      if (!dir.startsWith(root)) throw ApiException.bad("Invalid folder");
      Files.createDirectories(dir);
      String name = UUID.randomUUID() + "." + EXT.get(ct);
      try (InputStream in = file.getInputStream()) { Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
      String original = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._ -]", "_");
      return new Stored(folder + "/" + name, original, ct, file.getSize());
    } catch (IOException e) {
      throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not store file");
    }
  }

  static boolean magicMatches(String ct, byte[] h) {
    if (h.length < 4) return false;
    return switch (ct) {
      case "application/pdf" -> h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F';
      case "image/jpeg" -> (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
      case "image/png" -> (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
      default -> false;
    };
  }

  public Resource load(String storagePath) {
    Path p = root.resolve(storagePath).normalize();
    if (!p.startsWith(root) || !Files.exists(p)) throw ApiException.notFound("File not found");
    return new FileSystemResource(p);
  }
}
