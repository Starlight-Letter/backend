package com.starlight.greetings.application;

import com.starlight.greetings.web.ApiException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcEngagementService implements EngagementService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int BEST_SIZE = 10, BEST_CANDIDATES = 20;
    private static final Duration BEST_CACHE_TTL = Duration.ofSeconds(10);
    private record Ranked(String letterId, int reactionCount) {}
    private record CachedRanking(Instant expiresAt, List<Ranked> rows) {}
    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, CachedRanking> bestCache = new ConcurrentHashMap<>();

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
            SELECT l.id::text, l.bridge_id, l.status, l.body, l.display_nickname, l.theme, q.body AS question_body, s.created_at
            FROM saves s JOIN letters l ON l.id = s.letter_id
            LEFT JOIN questions q ON l.question_id = q.id
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
                    item.put("theme", rs.getInt("theme"));
                    item.put("questionBody", rs.getString("question_body"));
                }
                return item;
            }, userId);
    }

    // 내가 쓴 편지는 목록으로 돌려주지 않고, 띄운 수와 받은 위로·보관 수의 합계만 알려준다.
    @Override public Map<String, Object> letterTrace(String userId) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT (SELECT count(*)::int FROM letters WHERE author_user_id = ?::uuid AND status <> 'deleted') AS letters_sent,
                   (SELECT count(*)::int FROM reactions r JOIN letters l ON l.id = r.letter_id
                     WHERE l.author_user_id = ?::uuid AND l.status <> 'deleted' AND r.active = true
                       AND r.user_id IS DISTINCT FROM ?::uuid) AS comfort_received,
                   (SELECT count(*)::int FROM saves s JOIN letters l ON l.id = s.letter_id
                     WHERE l.author_user_id = ?::uuid AND l.status = 'published' AND s.user_id <> ?::uuid) AS kept_by_others
            """, userId, userId, userId, userId, userId);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("lettersSent", row.get("letters_sent"));
        trace.put("comfortReceived", row.get("comfort_received"));
        trace.put("keptByOthers", row.get("kept_by_others"));
        return trace;
    }

    @Override public List<Map<String, Object>> best(String scope, String bridgeId, String period) {
        if (!"all".equals(scope) && !"bridge".equals(scope)) throw invalid("INVALID_SCOPE", "범위를 다시 선택해 주세요.");
        if ("bridge".equals(scope) && (bridgeId == null || bridgeId.isBlank())) throw invalid("BRIDGE_REQUIRED", "다리를 선택해 주세요.");
        Instant now = Instant.now(); Instant start = periodStart(period, now);
        String bridge = "bridge".equals(scope) ? bridgeId : null;
        List<Ranked> ranking = ranking(bridge, period, start, now);
        if (ranking.isEmpty()) return List.of();
        // 순위(편지 id·반응 수)는 캐시에서 가져오더라도, 본문과 공개 상태는 매번 지금 값으로 읽는다.
        // 그래서 임시 숨김·삭제된 편지는 캐시와 상관없이 바로 서랍장에서 빠진다.
        Map<String, Map<String, Object>> letters = new LinkedHashMap<>();
        jdbc.query("""
            SELECT l.id::text, l.bridge_id, l.body, l.display_nickname, l.theme, q.body AS question_body
            FROM letters l LEFT JOIN questions q ON l.question_id = q.id
            WHERE l.id = ANY(string_to_array(?, ',')::uuid[]) AND l.status = 'published'
            """, rs -> {
                // 시작 편지는 질문이 없어 questionBody가 null이므로 null을 허용하는 맵을 쓴다.
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rs.getString("id")); item.put("bridgeId", rs.getString("bridge_id"));
                item.put("preview", preview(rs.getString("body"))); item.put("displayNickname", rs.getString("display_nickname"));
                item.put("theme", rs.getInt("theme")); item.put("questionBody", rs.getString("question_body"));
                letters.put(rs.getString("id"), item);
            }, String.join(",", ranking.stream().map(Ranked::letterId).toList()));
        List<Map<String, Object>> best = new ArrayList<>();
        for (Ranked ranked : ranking) {
            Map<String, Object> letter = letters.get(ranked.letterId());
            if (letter == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", best.size() + 1); item.putAll(letter); item.put("reactionCount", ranked.reactionCount());
            best.add(item);
            if (best.size() == BEST_SIZE) break;
        }
        return best;
    }

    // 서랍장은 손님이 한 바퀴 돌 때마다 부르는데, 순위 집계는 기간 안의 반응을 모두 읽는다(한 달 x3 데이터에서 커넥션 풀을 막아 사이트가 멈췄다).
    // 그래서 집계 결과만 짧게 캐시한다. 같은 조합의 요청이 동시에 와도 compute가 한 번만 집계한다.
    // 숨김 편지가 빠져도 10통을 채울 수 있게 후보를 넉넉히 뽑아 둔다.
    private List<Ranked> ranking(String bridgeId, String period, Instant start, Instant now) {
        if (bestCache.size() > 64) bestCache.clear(); // 없는 다리 id를 계속 바꿔 보내도 캐시가 끝없이 커지지 않게 한다
        String key = bridgeId + "|" + period + "|" + start;
        return bestCache.compute(key, (k, cached) -> cached != null && cached.expiresAt().isAfter(now) ? cached : new CachedRanking(now.plus(BEST_CACHE_TTL), jdbc.query("""
            SELECT r.letter_id::text, count(*)::int AS reaction_count
            FROM reactions r JOIN letters l ON l.id = r.letter_id
            WHERE r.active = true AND r.activated_at >= ? AND r.activated_at <= ?
              AND l.status = 'published' AND (?::text IS NULL OR l.bridge_id = ?)
            GROUP BY r.letter_id
            ORDER BY reaction_count DESC, max(r.activated_at) DESC, r.letter_id ASC
            LIMIT ?
            """, (rs, index) -> new Ranked(rs.getString(1), rs.getInt(2)),
            java.sql.Timestamp.from(start), java.sql.Timestamp.from(now), bridgeId, bridgeId, BEST_CANDIDATES))).rows();
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
