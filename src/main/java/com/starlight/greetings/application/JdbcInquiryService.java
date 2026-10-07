package com.starlight.greetings.application;

import com.starlight.greetings.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcInquiryService implements InquiryService {
 private static final Set<String> TYPES = Set.of("privacy", "service", "suggestion", "removal");
 private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
 private final JdbcTemplate jdbc;
 public JdbcInquiryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
 @Override public Map<String,Object> submit(Map<String,Object> input, String source) {
  String type = asString(input.get("type")); String body = asString(input.get("body"));
  if (!TYPES.contains(type)) throw invalid("INVALID_INQUIRY_TYPE", "문의 종류를 다시 선택해 주세요.");
  String requesterId = asString(input.get("requesterUserId"));
  if ("removal".equals(type) && requesterId == null) throw new ApiException(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED","로그인이 필요합니다.");
  if (body == null || body.trim().isEmpty() || body.length() > 2000) throw invalid("INVALID_INQUIRY_BODY", "문의 내용을 1~2000자로 입력해 주세요.");
  String email = asString(input.get("replyEmail")); if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) throw invalid("INVALID_EMAIL", "답변받을 이메일을 확인해 주세요.");
  String letterId = asString(input.get("relatedLetterId")); if (letterId != null) try { UUID.fromString(letterId); } catch (Exception e) { throw invalid("INVALID_LETTER", "관련 편지 정보를 확인해 주세요."); }
  Integer attempts = jdbc.queryForObject("""
   INSERT INTO auth_attempts (key_hash, attempts, window_started_at) VALUES (?, 1, now())
   ON CONFLICT (key_hash) DO UPDATE SET attempts = CASE WHEN auth_attempts.window_started_at < now() - interval '15 minutes' THEN 1 ELSE auth_attempts.attempts + 1 END,
   window_started_at = CASE WHEN auth_attempts.window_started_at < now() - interval '15 minutes' THEN now() ELSE auth_attempts.window_started_at END RETURNING attempts
   """, Integer.class, digest("inquiry-source:" + source));
  if (attempts != null && attempts > 5) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","잠시 후 다시 시도해 주세요.");
  String id = UUID.randomUUID().toString();
  try { jdbc.update("INSERT INTO inquiries (id,type,body,related_letter_id,requester_user_id,reply_email,status) VALUES (?::uuid,?, ?, ?::uuid, ?::uuid, ?, 'open')", id, type, body.trim(), letterId, requesterId, email); }
  catch (DataIntegrityViolationException e) { throw invalid("INVALID_LETTER", "관련 편지를 찾을 수 없습니다."); }
  return Map.of("id", id, "status", "open");
 }
 private String asString(Object value) { return value instanceof String text && !text.isBlank() ? text : null; }
 private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
 private String digest(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
