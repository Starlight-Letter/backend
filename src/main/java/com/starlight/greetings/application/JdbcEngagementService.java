package com.starlight.greetings.application;

import com.starlight.greetings.web.ApiException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcEngagementService implements EngagementService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;

    public JdbcEngagementService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Map<String, Object> state(String letterId, String userId) {
        published(letterId);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT EXISTS(SELECT 1 FROM reactions WHERE letter_id = ?::uuid AND user_id = ?::uuid AND active = true) AS reacted,
                   EXISTS(SELECT 1 FROM saves WHERE letter_id = ?::uuid AND user_id = ?::uuid) AS saved,
                   (SELECT count(*) FROM reactions WHERE letter_id = ?::uuid AND active = true) AS reaction_count
            """, letterId, userId, letterId, userId, letterId);
        return Map.of("reacted", row.get("reacted"), "saved", row.get("saved"), "reactionCount", ((Number) row.get("reaction_count")).intValue());
    }

    @Override public Map<String, Object> react(String letterId, String userId, boolean active) {
        published(letterId);
        if (active) {
            jdbc.update("""
                INSERT INTO reactions (id, letter_id, user_id, active, activated_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, true, now())
                ON CONFLICT (user_id, letter_id) WHERE user_id IS NOT NULL
                DO UPDATE SET active = true,
                  activated_at = CASE WHEN reactions.active THEN reactions.activated_at ELSE EXCLUDED.activated_at END
                """, UUID.randomUUID().toString(), letterId, userId);
        } else {
            jdbc.update("UPDATE reactions SET active = false WHERE letter_id = ?::uuid AND user_id = ?::uuid", letterId, userId);
        }
        Integer reactionCount = jdbc.queryForObject("SELECT count(*)::int FROM reactions WHERE letter_id = ?::uuid AND active = true", Integer.class, letterId);
        return Map.of("active", active, "reactionCount", reactionCount == null ? 0 : reactionCount);
    }

    @Override public Map<String, Object> save(String letterId, String userId, boolean saved) {
        validUuid(letterId);
        if (saved) {
            published(letterId);
            jdbc.update("INSERT INTO saves (user_id, letter_id) VALUES (?::uuid, ?::uuid) ON CONFLICT (user_id, letter_id) DO NOTHING", userId, letterId);
        } else {
            jdbc.update("DELETE FROM saves WHERE user_id = ?::uuid AND letter_id = ?::uuid", userId, letterId);
        }
        return Map.of("saved", saved);
    }

    @Override public List<Map<String, Object>> saved(String userId) {
        return jdbc.query("""
            SELECT l.id::text, l.bridge_id, l.status, l.body, l.display_nickname, s.created_at
            FROM saves s JOIN letters l ON l.id = s.letter_id
            WHERE s.user_id = ?::uuid AND l.status <> 'deleted'
            ORDER BY s.created_at DESC, l.id
            """, (rs, index) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rs.getString("id")); item.put("bridgeId", rs.getString("bridge_id"));
                item.put("savedAt", rs.getTimestamp("created_at").toInstant().toString());
                if ("hidden".equals(rs.getString("status"))) {
                    item.put("unavailable", true); item.put("message", "지금은 볼 수 없는 편지");
                } else {
                    item.put("unavailable", false); item.put("preview", preview(rs.getString("body")));
                    item.put("displayNickname", rs.getString("display_nickname"));
                }
                return item;
            }, userId);
    }

    @Override public List<Map<String, Object>> best(String scope, String bridgeId, String period) {
        if (!"all".equals(scope) && !"bridge".equals(scope)) throw invalid("INVALID_SCOPE", "범위를 다시 선택해 주세요.");
        if ("bridge".equals(scope) && (bridgeId == null || bridgeId.isBlank())) throw invalid("BRIDGE_REQUIRED", "다리를 선택해 주세요.");
        Instant now = Instant.now(); Instant start = periodStart(period, now);
        return jdbc.query("""
            SELECT l.id::text, l.bridge_id, l.body, l.display_nickname, l.theme,
                   count(r.id)::int AS reaction_count
            FROM reactions r JOIN letters l ON l.id = r.letter_id
            WHERE r.active = true AND r.activated_at >= ? AND r.activated_at <= ?
              AND l.status = 'published' AND (?::text IS NULL OR l.bridge_id = ?)
            GROUP BY l.id
            ORDER BY reaction_count DESC, max(r.activated_at) DESC, l.id ASC
            LIMIT 10
            """, (rs, index) -> Map.<String, Object>of(
                "rank", index + 1, "id", rs.getString("id"), "bridgeId", rs.getString("bridge_id"),
                "preview", preview(rs.getString("body")), "displayNickname", rs.getString("display_nickname"),
                "theme", rs.getInt("theme"), "reactionCount", rs.getInt("reaction_count")),
            java.sql.Timestamp.from(start), java.sql.Timestamp.from(now), "bridge".equals(scope) ? bridgeId : null, "bridge".equals(scope) ? bridgeId : null);
    }

    private void published(String letterId) {
        validUuid(letterId);
        Boolean found = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM letters WHERE id = ?::uuid AND status = 'published')", Boolean.class, letterId);
        if (!Boolean.TRUE.equals(found)) throw new ApiException(HttpStatus.NOT_FOUND, "LETTER_NOT_FOUND", "편지를 찾을 수 없습니다.");
    }
    private void validUuid(String value) { try { UUID.fromString(value); } catch (Exception e) { throw new ApiException(HttpStatus.NOT_FOUND, "LETTER_NOT_FOUND", "편지를 찾을 수 없습니다."); } }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private Instant periodStart(String period, Instant now) {
        ZonedDateTime time = now.atZone(KST);
        if ("month".equals(period)) return time.withDayOfMonth(1).toLocalDate().atStartOfDay(KST).toInstant();
        if ("week".equals(period)) { LocalDate monday = time.toLocalDate().with(DayOfWeek.MONDAY); return monday.atStartOfDay(KST).toInstant(); }
        throw invalid("INVALID_PERIOD", "기간을 다시 선택해 주세요.");
    }
    private String preview(String body) { return body == null ? "" : body.codePoints().limit(40).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(); }
}
