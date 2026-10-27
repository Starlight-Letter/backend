package com.starlight.greetings.domain.moderation;

/**
 * 내용 검사에 걸렸을 때 이용자에게 보여줄 안내. 무엇 때문에 막혔는지(걸린 표현·범주)를 알려 주고,
 * 다른 뜻으로 쓴 말이 우연히 걸린 경우를 위해 띄어쓰기·표현을 바꾸면 된다는 방법도 함께 알린다.
 */
public final class ModerationNotice {
    private ModerationNotice() {}

    private static final String REPHRASE = " 다른 뜻으로 쓰셨다면 띄어쓰기나 표현을 조금 바꿔 주세요.";

    /** subject: "편지" 또는 "닉네임" */
    public static String of(String subject, String category, String matched) {
        boolean letter = "편지".equals(subject);
        String fix = letter ? " 그 부분을 고쳐 주세요." : " 다른 닉네임을 써 주세요.";
        String quoted = matched == null ? null : "'" + matched + "'";
        return switch (category == null ? "" : category) {
            case "profanity" -> (quoted == null ? "욕설이나 비하 표현" : quoted + " 같은 욕설·비하 표현") + "이 들어 있어요." + fix + (quoted == null ? "" : REPHRASE);
            case "personal_info" -> quoted == null
                ? "전화번호나 이메일 같은 연락처는 " + subject + "에 쓸 수 없어요." + fix
                : quoted + "처럼 개인정보로 보이는 표현이 들어 있어요." + fix + REPHRASE;
            case "advertising" -> quoted == null
                ? "링크나 광고·홍보 문구는 " + subject + "에 쓸 수 없어요." + fix
                : quoted + "처럼 광고·홍보로 보이는 표현이 들어 있어요." + fix + REPHRASE;
            default -> (quoted == null ? "사용할 수 없는 표현" : quoted + "처럼 사용할 수 없는 표현") + "이 들어 있어요." + fix + (quoted == null ? "" : REPHRASE);
        };
    }
}
