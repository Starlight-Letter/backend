package com.starlight.greetings.application;

import com.starlight.greetings.web.LetterPreview;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LetterQueryService {
    private final JdbcTemplate jdbcTemplate;
    public LetterQueryService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }
    public List<LetterPreview> river(String bridgeId) {
        return jdbcTemplate.query("""
            select id, bridge_id, theme, is_seed, body, created_at from letters
            where bridge_id = ? and status = 'published' order by random() limit 5
            """, (row, index) -> {
                String body = row.getString("body");
                String preview = body.length() > 40 ? body.substring(0, 40) + "…" : body;
                return new LetterPreview(row.getString("id"), row.getString("bridge_id"), row.getInt("theme"), row.getBoolean("is_seed"), preview, row.getTimestamp("created_at").toInstant());
            }, bridgeId);
    }
}
