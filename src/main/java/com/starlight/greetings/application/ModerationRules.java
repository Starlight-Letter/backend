package com.starlight.greetings.application;

import com.starlight.greetings.domain.moderation.ModerationText;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 운영자 검사 규칙(moderation_rules)을 메모리에 들고 있는다. 편지 쓰기와 닉네임 확인이 부를 때마다 DB를 읽지 않도록 1분 캐시하고,
// 운영자가 규칙을 추가·끄면 invalidate()로 바로 다시 읽는다. 서버가 여러 대여도 최대 1분 안에 맞춰진다.
@Component
public class ModerationRules {
    private static final Duration TTL = Duration.ofMinutes(1);
    /** 걸린 규칙: 안내에 보여줄 문구와 범주(profanity·advertising·personal_info) */
    public record Hit(String phrase, String category) {}
    private record Rule(String key, String phrase, String category) {}
    private record Snapshot(Instant loadedAt, List<Rule> rules) {}
    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot;

    public ModerationRules(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 활성 규칙 중 input에 들어 있는 것이 있는지. 띄어 쓰거나 기호·숫자를 끼운 표기까지 본다(ModerationText) */
    public boolean matches(String input) { return find(input).isPresent(); }

    /** 처음 걸린 규칙(없으면 empty) */
    public Optional<Hit> find(String input) {
        ModerationText text = ModerationText.of(input);
        return rules().stream().filter((rule) -> text.contains(rule.key())).findFirst().map((rule) -> new Hit(rule.phrase(), rule.category()));
    }

    public void invalidate() { snapshot = null; }

    private List<Rule> rules() {
        Snapshot current = snapshot;
        if (current == null || current.loadedAt().plus(TTL).isBefore(Instant.now())) {
            current = new Snapshot(Instant.now(), jdbc.query("select phrase, category from moderation_rules where active = true",
                (r, i) -> new Rule(ModerationText.key(r.getString(1)), r.getString(1), r.getString(2))));
            snapshot = current;
        }
        return current.rules();
    }

    static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.KOREAN); }
}
