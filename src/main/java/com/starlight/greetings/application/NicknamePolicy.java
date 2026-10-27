package com.starlight.greetings.application;

import com.starlight.greetings.domain.moderation.ContentModerator;
import com.starlight.greetings.domain.moderation.ModerationNotice;
import com.starlight.greetings.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// 닉네임은 익명이 아닌 편지에 서명으로 공개되므로 편지 본문과 같은 검사(기본 욕설·연락처·광고 + 운영자 검사 규칙)를 거친다.
// 공식 서명('별빛안부', '익명의 별빛')이나 운영자를 사칭하는 이름도 막는다. 띄어쓰기·특수문자를 끼워 피하지 못하게 붙여서 비교한다.
@Component
public class NicknamePolicy {
    private static final List<String> RESERVED = List.of("별빛안부", "익명의별빛", "운영자", "관리자", "운영팀");
    private static final String RESERVED_MESSAGE = "공식 서명이나 운영자로 오해할 수 있는 닉네임은 쓸 수 없어요.";
    // problem: 사유 코드, category·matched: 안내에 쓸 범주와 걸린 표현
    private record Finding(String problem, String category, String matched) {}
    private final ContentModerator moderator;
    private final ModerationRules rules;

    public NicknamePolicy(ContentModerator moderator, ModerationRules rules) { this.moderator = moderator; this.rules = rules; }

    /** 문제가 없으면 null, 있으면 사유 코드(reserved·profanity·personal_info·advertising·rule) */
    public String problem(String display) {
        Finding finding = find(display);
        return finding == null ? null : finding.problem();
    }

    /** 문제가 없으면 null, 있으면 무엇 때문에 쓸 수 없는지 알려 주는 안내 */
    public String explain(String display) {
        Finding finding = find(display);
        if (finding == null) return null;
        return "reserved".equals(finding.problem()) ? RESERVED_MESSAGE : ModerationNotice.of("닉네임", finding.category(), finding.matched());
    }

    public void require(String display) {
        String message = explain(display);
        if (message != null) throw new ApiException(HttpStatus.BAD_REQUEST, "NICKNAME_NOT_ALLOWED", message);
    }

    private Finding find(String display) {
        String compact = ModerationRules.normalize(display).replaceAll("[\\s\\p{Z}\\p{P}\\p{S}\\u200B-\\u200D\\uFEFF]", "");
        // 영문 admin은 'badminton' 같은 말까지 막지 않도록 앞부분만 본다
        if (RESERVED.stream().anyMatch(compact::contains) || compact.startsWith("admin")) return new Finding("reserved", null, null);
        ContentModerator.Result base = moderator.check(display);
        if (base != null && !"length".equals(base.category())) return new Finding(base.category(), base.category(), base.matched());
        // 닉네임은 짧아서 띄어쓰기를 모두 지운 형태로도 본다
        var hit = rules.find(display).or(() -> rules.find(compact));
        return hit.map((h) -> new Finding("rule", h.category(), h.phrase())).orElse(null);
    }
}
