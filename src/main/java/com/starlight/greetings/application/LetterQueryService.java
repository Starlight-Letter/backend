package com.starlight.greetings.application;

import com.starlight.greetings.web.ApiException;
import com.starlight.greetings.web.LetterPreview;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LetterQueryService {
    // 이미 본 편지(이 기기에서 읽었거나 내가 쓴 편지)도 강물에서 빼지는 않고, 안 본 편지가 뽑힐 확률만 이만큼 높인다
    static final int UNSEEN_WEIGHT = 20;
    static final int MAX_SEEN = 60;
    // 먼저 지금처럼 무작위 후보 20통을 고르고, 그 안에서만 가중 무작위 추출(power(random(), 1/w) 큰 순)로 5통을 고른다.
    // 편지가 20통 이하인 초반에는 전체에 정확히 가중이 걸리고, 편지가 많을 때도 모든 행에 seen 비교를 하지 않아 느려지지 않는다.
    // float8로 나눠야 한다(1.0 / int는 numeric이라 power가 눈에 띄게 느려진다).
    private static final String CANDIDATES = " ORDER BY random() LIMIT 20) candidates ";
    private static final String WEIGHTED_ORDER = "ORDER BY power(random(), 1.0::float8 / CASE WHEN id = ANY(?::uuid[]) THEN 1 ELSE " + UNSEEN_WEIGHT + " END) DESC LIMIT 5";
    private static final String PICK = "SELECT id::text AS id, bridge_id, theme, is_seed, body, created_at FROM (SELECT id, bridge_id, theme, is_seed, body, created_at FROM letters ";
    private final JdbcTemplate jdbc;
    public LetterQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<LetterPreview> river(String bridgeId) { return river(bridgeId, List.of()); }
    public List<LetterPreview> river(String bridgeId, List<String> seen) {
        String seenArray = "{" + String.join(",", validSeen(seen)) + "}";
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bridges WHERE id = ? AND active = true)", Boolean.class, bridgeId);
        if (!Boolean.TRUE.equals(exists)) throw new ApiException(HttpStatus.NOT_FOUND, "BRIDGE_NOT_FOUND", "다리를 찾을 수 없습니다.");
        List<String> questions = jdbc.query("""
            SELECT id::text FROM questions WHERE bridge_id = ? AND status = 'published' AND effective_at <= now()
            ORDER BY effective_at DESC, id DESC LIMIT 1
            """, (rs, row) -> rs.getString(1), bridgeId);
        List<LetterPreview> members = questions.isEmpty() ? List.of() : letters(PICK
            + "WHERE bridge_id = ? AND question_id = ?::uuid AND is_seed = false AND status = 'published'"
            + CANDIDATES + WEIGHTED_ORDER, bridgeId, questions.get(0), seenArray);
        List<LetterPreview> seeds = letters(PICK
            + "WHERE bridge_id = ? AND is_seed = true AND status = 'published'"
            + CANDIDATES + WEIGHTED_ORDER, bridgeId, seenArray);
        List<LetterPreview> selected = new ArrayList<>();
        if (members.size() >= 4 && !seeds.isEmpty()) { selected.addAll(members.subList(0, 4)); selected.add(seeds.get(0)); }
        else { selected.addAll(members); selected.addAll(seeds.subList(0, Math.min(5 - members.size(), seeds.size()))); }
        Collections.shuffle(selected);
        return selected;
    }
    // 배열 리터럴에 그대로 넣으므로 UUID로 읽히는 값만, 중복 없이 MAX_SEEN개까지 남긴다
    static List<String> validSeen(List<String> seen) {
        if (seen == null) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String raw : seen) {
            if (ids.size() >= MAX_SEEN) break;
            if (raw == null || raw.length() != 36) continue;
            try { ids.add(UUID.fromString(raw).toString()); } catch (IllegalArgumentException ignored) { /* 형식이 틀린 값은 버린다 */ }
        }
        return new ArrayList<>(ids);
    }
    private List<LetterPreview> letters(String sql, Object... args) { return jdbc.query(sql, (row, index) -> new LetterPreview(row.getString("id"), row.getString("bridge_id"), row.getInt("theme"), row.getBoolean("is_seed"), preview(row.getString("body")), row.getTimestamp("created_at").toInstant()), args); }
    private String preview(String body) { long length = body.codePoints().count(); String clipped = body.codePoints().limit(40).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(); return length > 40 ? clipped + "…" : clipped; }
}
