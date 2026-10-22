package com.starlight.greetings.application;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 운영자 검사 규칙(moderation_rules)을 메모리에 들고 있는다. 편지 쓰기와 닉네임 확인이 부를 때마다 DB를 읽지 않도록 1분 캐시하고,
// 운영자가 규칙을 추가·끄면 invalidate()로 바로 다시 읽는다. 서버가 여러 대여도 최대 1분 안에 맞춰진다.
@Component
public class ModerationRules {
    private static final Duration TTL = Duration.ofMinutes(1);
    private record Snapshot(Instant loadedAt, List<String> phrases) {}
    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot;

    public ModerationRules(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 활성 규칙 중 input에 들어 있는 것이 있는지. 비교는 NFKC·소문자 기준 */
    public boolean matches(String input) {
        String searchable = normalize(input);
        return phrases().stream().anyMatch(searchable::contains);
    }

    public void invalidate() { snapshot = null; }

    private List<String> phrases() {
        Snapshot current = snapshot;
        if (current == null || current.loadedAt().plus(TTL).isBefore(Instant.now())) {
            current = new Snapshot(Instant.now(), jdbc.query("select phrase from moderation_rules where active = true", (r, i) -> normalize(r.getString(1))));
            snapshot = current;
        }
        return current.phrases();
    }

    static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.KOREAN); }
}
