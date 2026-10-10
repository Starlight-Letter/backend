package com.starlight.greetings.web;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/letters")
public class LetterController {
    private final JdbcTemplate jdbc;

    public LetterController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{id}")
    public ApiEnvelope<LetterDetail> detail(@PathVariable String id) {
        List<LetterDetail> rows = jdbc.query(
            "select l.id, l.bridge_id, l.display_nickname, l.body, l.theme, l.is_seed, l.created_at, q.body as question_body " +
            "from letters l left join questions q on l.question_id = q.id " +
            "where l.id = ?::uuid and l.status = 'published'",
            (r, i) -> new LetterDetail(
                r.getString("id"),
                r.getString("bridge_id"),
                r.getString("display_nickname"),
                r.getString("body"),
                r.getInt("theme"),
                r.getBoolean("is_seed"),
                r.getTimestamp("created_at").toInstant(),
                r.getString("question_body")
            ),
            id
        );
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "LETTER_NOT_FOUND", "편지를 찾을 수 없습니다.");
        return ApiEnvelope.of(rows.get(0));
    }
}
