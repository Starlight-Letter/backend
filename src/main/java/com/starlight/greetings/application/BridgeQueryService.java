package com.starlight.greetings.application;

import com.starlight.greetings.web.BridgeView;
import com.starlight.greetings.web.BridgeQuestion;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class BridgeQueryService {
    private final JdbcTemplate jdbcTemplate;

    public BridgeQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BridgeView> list() {
        Instant now = Instant.now();
        return jdbcTemplate.query("""
            select b.id, b.name, b.display_order, q.id as question_id, q.body as question_body, q.effective_at
            from bridges b left join lateral (
              select id, body, effective_at from questions
              where bridge_id = b.id and status = 'published' and effective_at <= ?
              order by effective_at desc, id desc limit 1
            ) q on true
            where b.active = true order by b.display_order
            """, (row, index) -> new BridgeView(
                row.getString("id"), row.getString("name"), row.getInt("display_order"),
                row.getString("question_id") == null ? null : new BridgeQuestion(
                    row.getString("question_id"), row.getString("question_body"), row.getTimestamp("effective_at").toInstant()
                )
            ), Timestamp.from(now));
    }
}
