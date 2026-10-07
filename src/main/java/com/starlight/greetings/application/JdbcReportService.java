package com.starlight.greetings.application;

import com.starlight.greetings.web.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcReportService implements ReportService {
 private static final Set<String> REASONS = Set.of("inappropriate", "advertising", "personal_info", "other");
 private final JdbcTemplate jdbc;
 public JdbcReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
 @Transactional @Override public Map<String,Object> report(String letterId, String userId, String reasonCode) {
  if (!REASONS.contains(reasonCode)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REASON","신고 사유를 다시 선택해 주세요.");
  try { UUID.fromString(letterId); } catch (Exception e) { throw missing(); }
  var letters = jdbc.query("SELECT status, report_round FROM letters WHERE id = ?::uuid FOR UPDATE", (rs, row) -> new Object[] {rs.getString(1), rs.getInt(2)}, letterId);
  if (letters.isEmpty() || !"published".equals(letters.get(0)[0])) throw missing();
  int round = (Integer) letters.get(0)[1];
  try {
   jdbc.update("INSERT INTO reports (id, letter_id, reporter_user_id, round, reason_code, status) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'pending')", UUID.randomUUID().toString(), letterId, userId, round, reasonCode);
  } catch (DuplicateKeyException e) { throw new ApiException(HttpStatus.CONFLICT,"ALREADY_REPORTED","이미 신고한 편지입니다."); }
  Integer count = jdbc.queryForObject("SELECT count(*)::int FROM reports WHERE letter_id = ?::uuid AND round = ? AND status = 'pending'", Integer.class, letterId, round);
  boolean hidden = count != null && count >= 5;
  if (hidden) jdbc.update("UPDATE letters SET status = 'hidden', hidden_at = now() WHERE id = ?::uuid", letterId);
  return Map.of("hidden", hidden, "reportCount", count == null ? 0 : count);
 }
 private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"LETTER_NOT_FOUND","편지를 찾을 수 없습니다."); }
}
