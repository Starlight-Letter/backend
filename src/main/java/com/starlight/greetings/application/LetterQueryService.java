package com.starlight.greetings.application;

import com.starlight.greetings.web.ApiException;
import com.starlight.greetings.web.LetterPreview;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LetterQueryService {
    private final JdbcTemplate jdbc;
    public LetterQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<LetterPreview> river(String bridgeId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bridges WHERE id = ? AND active = true)", Boolean.class, bridgeId);
        if (!Boolean.TRUE.equals(exists)) throw new ApiException(HttpStatus.NOT_FOUND, "BRIDGE_NOT_FOUND", "다리를 찾을 수 없습니다.");
        List<String> questions = jdbc.query("""
            SELECT id::text FROM questions WHERE bridge_id = ? AND status = 'published' AND effective_at <= now()
            ORDER BY effective_at DESC, id DESC LIMIT 1
            """, (rs, row) -> rs.getString(1), bridgeId);
        List<LetterPreview> members = questions.isEmpty() ? List.of() : letters("""
            SELECT id::text, bridge_id, theme, is_seed, body, created_at FROM letters
            WHERE bridge_id = ? AND question_id = ?::uuid AND is_seed = false AND status = 'published'
            ORDER BY random() LIMIT 5
            """, bridgeId, questions.get(0));
        List<LetterPreview> seeds = letters("""
            SELECT id::text, bridge_id, theme, is_seed, body, created_at FROM letters
            WHERE bridge_id = ? AND is_seed = true AND status = 'published' ORDER BY random() LIMIT 5
            """, bridgeId);
        List<LetterPreview> selected = new ArrayList<>();
        if (members.size() >= 4 && !seeds.isEmpty()) { selected.addAll(members.subList(0, 4)); selected.add(seeds.get(0)); }
        else { selected.addAll(members); selected.addAll(seeds.subList(0, Math.min(5 - members.size(), seeds.size()))); }
        Collections.shuffle(selected);
        return selected;
    }
    private List<LetterPreview> letters(String sql, Object... args) { return jdbc.query(sql, (row, index) -> new LetterPreview(row.getString("id"), row.getString("bridge_id"), row.getInt("theme"), row.getBoolean("is_seed"), preview(row.getString("body")), row.getTimestamp("created_at").toInstant()), args); }
    private String preview(String body) { long length = body.codePoints().count(); String clipped = body.codePoints().limit(40).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(); return length > 40 ? clipped + "…" : clipped; }
}
