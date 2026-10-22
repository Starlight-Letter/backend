package com.starlight.greetings.application;

import com.starlight.greetings.domain.moderation.ContentModerator;
import com.starlight.greetings.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// 닉네임은 익명이 아닌 편지에 서명으로 공개되므로 편지 본문과 같은 검사(기본 욕설·연락처·광고 + 운영자 검사 규칙)를 거친다.
// 공식 서명('별빛안부', '익명의 별빛')이나 운영자를 사칭하는 이름도 막는다. 띄어쓰기·특수문자를 끼워 피하지 못하게 붙여서 비교한다.
@Component
public class NicknamePolicy {
    private static final List<String> RESERVED = List.of("별빛안부", "익명의별빛", "운영자", "관리자", "운영팀");
    private static final Map<String, String> MESSAGES = Map.of(
        "reserved", "공식 서명이나 운영자로 오해할 수 있는 닉네임은 쓸 수 없어요.",
        "profanity", "욕설이나 비하 표현이 들어간 닉네임은 쓸 수 없어요.",
        "personal_info", "연락처나 이메일이 들어간 닉네임은 쓸 수 없어요.",
        "advertising", "광고나 홍보로 보이는 닉네임은 쓸 수 없어요.",
        "rule", "사용할 수 없는 표현이 들어 있어요. 다른 닉네임을 써 주세요.");
    private final ContentModerator moderator;
    private final ModerationRules rules;

    public NicknamePolicy(ContentModerator moderator, ModerationRules rules) { this.moderator = moderator; this.rules = rules; }

    /** 문제가 없으면 null, 있으면 사유 코드(reserved·profanity·personal_info·advertising·rule) */
    public String problem(String display) {
        String compact = ModerationRules.normalize(display).replaceAll("[\\s\\p{Z}\\p{P}\\p{S}\\u200B-\\u200D\\uFEFF]", "");
        // 영문 admin은 'badminton' 같은 말까지 막지 않도록 앞부분만 본다
        if (RESERVED.stream().anyMatch(compact::contains) || compact.startsWith("admin")) return "reserved";
        String category = moderator.category(display);
        if (category != null && !"length".equals(category)) return category;
        if (rules.matches(display) || rules.matches(compact)) return "rule";
        return null;
    }

    public String message(String problem) { return MESSAGES.getOrDefault(problem, MESSAGES.get("rule")); }

    public void require(String display) {
        String problem = problem(display);
        if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, "NICKNAME_NOT_ALLOWED", message(problem));
    }
}
