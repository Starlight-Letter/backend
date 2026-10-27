package com.starlight.greetings.domain.moderation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 실제 운영 규칙 목록(ops/moderation_rules_seed.sql)과 기본 검사를 함께 걸었을 때
 * ① 평범한 편지는 하나도 막히지 않고 ② 흔한 우회 표기는 막히는지 확인한다.
 * 규칙을 새로 넣기 전에도 이 테스트로 오탐을 확인한다(걸리면 어떤 문장이 어떤 표현에 걸렸는지 출력).
 */
class ModerationFalsePositiveTest {
    private static final Pattern RULE = Pattern.compile("\\('(?:profanity|advertising|personal_info)', '([^']+)'\\)");
    private final ContentModerator moderator = new ContentModerator();

    // 이 서비스에 올라올 만한 편지와, 금지 표현을 품었지만 뜻이 다른 함정 문장
    private static final List<String> ORDINARY = List.of(
        // 단어 경계를 넘으면 금지 표현처럼 보이는 문장
        "다시 발견한 마음이에요.", "다시 발걸음을 옮겨 봐요.", "솜씨 발휘한 하루였어요.", "실력 발휘는 내일 해도 돼요.",
        "새로운 시발점이 될 거예요.", "필요 시 발생하는 일은 그때 생각해요.", "병 신경 쓰지 말고 푹 쉬어요.", "다시 팔을 뻗어 봐요.",
        "아저씨 바지가 너무 귀여웠어요.", "아가씨 바보 같다고 웃었어요.", "아저씨 팔에 안긴 아이가 웃었어요.", "보존 나무 아래에서 쉬었어요.",
        "또 라이브 보러 가고 싶어요.", "또 라이언 인형을 샀어요.", "카톡 아이디어 회의가 길었어요.", "온라인 아이디어 공모전에 냈어요.",
        "바 카라멜 마키아토가 달았어요.", "독서 리딩 방법을 바꿨어요.", "새 가게 오픈 예정이래요.", "우리 개 새벽 산책 같이 가요.",
        "니 미래는 밝을 거야.", "10대 출근길 생각이 났어요.", "야 동생아 밥 먹었니?", "첫돌 아이 선물을 골랐어요.",
        // 금지 표현이 들어 있는 평범한 낱말
        "새끼 고양이가 다리 밑에 있었어요.", "강아지 새끼들이 꼬물거려요.", "시바견이 꼬리를 흔들었어요.", "토토로 다시 보고 싶어요.",
        "코인노래방에서 목이 쉬었어요.", "인스타에서 본 노을이 예뻤어요.", "시간을 투자한 만큼 자랐어요.", "마감이 닥쳐오니 정신이 없어요.",
        "시험이 닥쳐서 밤을 새웠어요.", "기분이 급전환됐어요.", "응급전화를 받고 달려갔어요.", "친구가 밥값 먹튀했어요 ㅋㅋ",
        "미친 듯이 좋았던 하루예요.", "꺼져 가는 불빛을 보며 걸었어요.", "걸레질하고 나니 개운해요.", "그 영화 보지 못했어요.",
        "어제 잠을 자지 못했어요.", "재택 알바 하면서 버텼어요.", "친구가 주식 추천해줬는데 망했어요.", "드라마 카지노 봤어요.",
        "엄마 계좌번호로 용돈 보냈어요.", "카톡으로 연락했어요.", "채팅으로 오래 이야기했어요.", "텔레비전 보다가 잠들었어요.",
        "최고의 수익률보다 마음이 중요해요.", "구독자 백 명 됐어요!",
        // 숫자·기호가 섞인 평범한 문장
        "2층 3호에 사는 이웃이 인사해 줬어요.", "10월 29일, 3시 15분에 다리를 건넜어요.", "7번 버스를 놓쳤어요...!", "ㅋㅋㅋ 오늘 진짜 웃겼다 ㅎㅎ",
        "ㅠㅠ 그래도 괜찮아요.", "1년 전의 나에게♡", "오늘의 점수: 100점!", "#힘내 #응원해", "(다들) 고생 많았어요~",
        // 실제로 넣은 시작 편지·회원 편지
        "편의점 앞에서 비를 피하고 있었는데, 사장님이 \"금방 그칠 거예요\" 하시면서 의자를 내주셨어요.", "엘리베이터에서 처음 본 이웃이 \"오늘 많이 춥죠\" 하고 먼저 인사해 주셨어요.",
        "다리 밑을 지날 때마다 같은 자리에 앉아 있는 고양이가 있어요.", "혼밥하던 식당 사장님이 밥을 조금 더 얹어 주시면서 \"많이 먹어요\" 하셨어요.",
        "잘하고 있다는 말은 자주 들어도 괜찮대요.", "저도 이 동네에서 오래 버텼어요. 결과가 늦게 와도 그동안 쌓은 시간은 어디 가지 않더라고요.",
        "불 꺼진 창문 사이로 아직 켜진 불빛을 보면 괜히 힘이 나요.", "잘 안 풀리는 날도 있죠. 그래도 여기까지 걸어온 걸음은 진짜예요.",
        "오늘은 남 말고 나한테 써 봐요. 밥은 챙겨 먹었는지, 너무 늦게 자진 않는지.", "요즘의 나에게. 다 잘하려고 애쓰느라 고생 많았어.",
        "올해 처음 계획했던 것들, 다 이루진 못했어도 괜찮아.", "가끔은 아무것도 안 하고 강물 소리만 들어도 돼.",
        "출근길에 버스 기사님이 뛰어오는 저를 기다려 주셨어요.", "동생이 말없이 제 방 책상에 귤 두 개를 올려 두고 갔어요.",
        "카페 알바생분이 제 컵에 작은 별을 그려 주셨어요.", "길을 헤매는데 지나가던 분이 같은 방향이라며 골목 끝까지 같이 걸어 주셨어요.",
        "감기에 걸렸다고 했더니 친구가 따뜻한 수프랑 약을 문 앞에 두고 갔어요.", "택배 기사님이 비 온다고 상자를 비닐로 한 번 더 감싸 주셨어요.",
        "회의에서 실수했는데 옆자리 선배가 괜찮다고 쪽지를 건네줬어요.", "엄마가 전화로 밥 잘 챙겨 먹으라는 말만 세 번 하셨어요.",
        "올해의 나에게. 이사도 하고 일도 바뀌고 정신없었는데 잘 적응했어.", "많이 울기도 했지만 그만큼 많이 웃기도 했던 한 해였어.",
        "새로운 걸 시작하는 게 무서웠는데 결국 해냈잖아.", "가끔 쉬어 가도 괜찮다는 걸 올해 처음 배웠어.",
        "매일 늦게 퇴근하면서도 운동을 놓지 않았던 나, 정말 대단해.", "자취 삼 년 차, 이제 김치찌개는 제법 맛있게 끓여.",
        "버티는 것만으로도 충분히 잘하고 있는 거예요.", "지금 힘든 건 당신이 약해서가 아니라 그만큼 애쓰고 있어서예요.",
        "시험 준비하는 모든 분들, 손 시리지 않게 장갑 꼭 챙겨요.", "괜찮지 않아도 괜찮아요. 오늘 하루를 다 끝낸 것만으로도 박수 받을 일이에요.",
        "추운 날 늦게까지 일하는 분들께. 집에 가는 길이 조금은 덜 춥기를 바라요.", "입김이 하얗게 나오는 계절이에요.",
        "혼자 버티는 것 같아도 이 강물 위에는 같은 마음들이 흘러요.",
        // 그 밖의 일상 편지
        "오늘 하루도 수고 많았어요. 따뜻한 차 한 잔 하고 푹 자요.", "시험 결과가 안 좋아서 속상했지만 다시 해 볼게요.", "다음 주에 면접이 있어요. 떨리지만 잘할 수 있겠죠?",
        "엄마 아빠 보고 싶어요. 이번 주말엔 집에 가야겠어요.", "친구랑 싸웠는데 먼저 사과할 용기가 생겼어요.", "고양이 사진 보면서 힘냈어요.",
        "신림동에 이사 온 지 일 년이 됐어요.", "도림천 따라 걷다 보면 마음이 가라앉아요.", "봉림교 아래 불빛이 참 예뻐요.", "신림2교에서 이 편지를 띄워요.",
        "알바 끝나고 집에 가는 길이에요. 오늘도 버텼다!", "취업 준비가 길어지지만 포기하지 않을래요.", "누군가 이 편지를 읽고 조금이라도 웃었으면 좋겠어요.",
        "비 오는 날엔 괜히 마음이 차분해져요.", "오늘 처음으로 혼자 영화를 봤어요.", "할머니가 해 주신 된장찌개가 생각나요.", "운동 삼 일 차, 근육통이 장난 아니에요.",
        "새벽에 깨서 이 편지를 써요. 다들 잘 자고 있나요?", "커피 대신 물을 마시기로 했어요.", "좋아하는 노래를 반복해서 들었어요.", "실수해도 괜찮다고 나에게 말해 줬어요.",
        "오늘은 그냥 아무 말이나 하고 싶었어요.", "우울했는데 강아지가 옆에 와서 기대 줬어요.", "졸업까지 한 학기 남았어요!", "팀플 발표 무사히 끝났어요.",
        "편지 쓰는 게 이렇게 설레는 일인지 몰랐어요.", "멀리 사는 친구에게 안부를 전하고 싶어요.", "나 자신에게 고생했다고 말해 주고 싶어요."
    );

    // 흔한 우회 표기: 하나라도 통과하면 안 된다
    private static final List<String> EVASIONS = List.of(
        "씨 발", "씨.발", "씨1발", "씨 1 발", "ㅅ ㅂ", "ㅅ.ㅂ", "병 신", "병.신", "개 새 끼", "개 새끼야",
        "미친 놈아", "지 랄", "ㅈ ㄴ", "존 나 좋아", "또.라.이", "니 애 미",
        "오픈 채팅으로 와요", "오픈 채팅방 링크 줄게요", "오.픈.카.톡", "카톡 아이디는 비밀", "텔 레 그 램", "맞.팔 해요",
        "번호는 010.1234.5678", "공 일 공 일이삼사", "bit.ly/abc 들어와", "토토 사이트 추천"
    );

    private List<String> rules() throws Exception {
        List<String> keys = new ArrayList<>();
        var matcher = RULE.matcher(Files.readString(Path.of("ops/moderation_rules_seed.sql")));
        while (matcher.find()) keys.add(matcher.group(1));
        return keys;
    }

    // 걸리면 걸린 이유("기본:범주" 또는 "규칙:문구"), 아니면 null
    private String blockedBy(String text, List<String> rules) {
        ContentModerator.Result base = moderator.check(text);
        if (base != null) return "기본:" + base.category() + (base.matched() == null ? "" : "(" + base.matched() + ")");
        ModerationText target = ModerationText.of(text);
        return rules.stream().filter((rule) -> target.contains(ModerationText.key(rule))).findFirst().map((rule) -> "규칙:" + rule).orElse(null);
    }

    @Test void readsTheRealRuleList() throws Exception {
        assertTrue(rules().size() >= 60, "운영 규칙 SQL에서 규칙을 읽지 못했다");
    }

    @Test void ordinaryLettersAreNeverBlocked() throws Exception {
        List<String> rules = rules();
        List<String> wrong = ORDINARY.stream().filter((text) -> blockedBy(text, rules) != null).map((text) -> text + "  ←  " + blockedBy(text, rules)).toList();
        assertTrue(wrong.isEmpty(), "평범한 편지가 막혔다:\n" + String.join("\n", wrong));
    }

    @Test void commonEvasionsAreBlocked() throws Exception {
        List<String> rules = rules();
        List<String> missed = EVASIONS.stream().filter((text) -> blockedBy(text, rules) == null).toList();
        assertTrue(missed.isEmpty(), "우회 표기가 통과했다:\n" + String.join("\n", missed));
    }

    @Test void blockedLettersExplainWhy() {
        assertEquals("'씨발' 같은 욕설·비하 표현이 들어 있어요. 그 부분을 고쳐 주세요. 다른 뜻으로 쓰셨다면 띄어쓰기나 표현을 조금 바꿔 주세요.",
            ModerationNotice.of("편지", "profanity", moderator.check("씨 발").matched()));
        assertEquals("'오픈채팅'처럼 광고·홍보로 보이는 표현이 들어 있어요. 그 부분을 고쳐 주세요. 다른 뜻으로 쓰셨다면 띄어쓰기나 표현을 조금 바꿔 주세요.",
            ModerationNotice.of("편지", "advertising", "오픈채팅"));
        assertEquals("전화번호나 이메일 같은 연락처는 편지에 쓸 수 없어요. 그 부분을 고쳐 주세요.",
            ModerationNotice.of("편지", moderator.check("번호는 010.1234.5678").category(), null));
        assertEquals("'오픈채팅'처럼 광고·홍보로 보이는 표현이 들어 있어요. 다른 닉네임을 써 주세요. 다른 뜻으로 쓰셨다면 띄어쓰기나 표현을 조금 바꿔 주세요.",
            ModerationNotice.of("닉네임", "advertising", "오픈채팅"));
    }
}
