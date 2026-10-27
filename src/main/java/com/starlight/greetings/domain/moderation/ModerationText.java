package com.starlight.greetings.domain.moderation;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 금지 표현을 우회 표기까지 잡으면서, 평범한 편지는 막지 않기 위한 비교용 문자열.
 * <ul>
 *   <li>기호·이모지·보이지 않는 문자를 지운다: 씨.발, 씨*발, 오픈_채팅, bit.ly</li>
 *   <li>한글 사이에 낀 숫자를 지운다: 씨1발, 병1신</li>
 *   <li>한 글자짜리 조각끼리만 붙인다: 씨 발, ㅅ ㅂ, 개 새 끼 (다시 발견처럼 두 글자 이상 낱말은 붙이지 않는다)</li>
 *   <li>3글자 이상 한글 표현은 띄어 쓴 낱말들에 걸쳐서도 찾는다: 오픈 채팅, 미친 놈, 카톡 아이디는.
 *       단 표현이 낱말 처음에서 시작해야 하고, 마지막 낱말에 남는 글자는 조사·어미여야 한다.
 *       그래서 "또 라이브"는 또라이, "다시 발견"은 시발로 보지 않는다.</li>
 *   <li>금지 표현을 품은 평범한 낱말(시발점 등)은 먼저 지운다.</li>
 * </ul>
 */
public final class ModerationText {
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{P}\\p{S}\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern DIGITS_IN_HANGUL = Pattern.compile("(?<=\\p{IsHangul})\\d+(?=\\p{IsHangul})");
    private static final Pattern ASCII_LETTER = Pattern.compile("[a-z]");
    // 금지 표현이 들어 있지만 뜻이 다른 평범한 낱말
    private static final List<String> ORDINARY_WORDS = List.of("시발점", "시발역");
    // 띄어 쓴 표현 뒤에 바로 붙어도 되는 조사·어미(카톡 아이디는, 오픈 채팅으로, 미친 놈아)
    private static final List<String> PARTICLES = List.of("으로", "에서", "한테", "에게", "까지", "부터", "처럼", "보다", "하고", "이랑", "이야", "이요",
        "은", "는", "이", "가", "을", "를", "에", "의", "도", "로", "와", "과", "랑", "께", "야", "아", "요", "들", "만");

    private final String spaced;
    private final String joined;
    private final String[] words;

    private ModerationText(String spaced, String joined) {
        this.spaced = spaced; this.joined = joined; this.words = joined.isEmpty() ? new String[0] : joined.split(" ");
    }

    public static ModerationText of(String input) {
        String text = base(input);
        for (String word : ORDINARY_WORDS) text = text.replace(word, " ");
        String spaced = WHITESPACE.matcher(SEPARATORS.matcher(text).replaceAll("")).replaceAll(" ").trim();
        return new ModerationText(dropDigitsInWords(spaced), dropDigitsInWords(joinSingleCharacterWords(spaced)));
    }

    /** 규칙 문구를 비교용으로 맞춘다(띄어쓰기·기호를 지운다). 남는 글자가 없으면 빈 문자열 */
    public static String key(String phrase) {
        return WHITESPACE.matcher(SEPARATORS.matcher(base(phrase)).replaceAll("")).replaceAll("");
    }

    /** 기호·공백만 지운 문자열(숫자는 그대로). 010.1234.5678 같은 번호 검사용 */
    public static String stripped(String input) {
        return WHITESPACE.matcher(SEPARATORS.matcher(base(input)).replaceAll("")).replaceAll("");
    }

    /** key()로 맞춘 문구가 들어 있는지 */
    public boolean contains(String key) {
        if (key.isEmpty()) return false;
        if (spaced.contains(key) || joined.contains(key)) return true;
        if (key.codePointCount(0, key.length()) < 3 || ASCII_LETTER.matcher(key).find()) return false;
        for (int start = 0; start < words.length; start++) if (spansWords(key, start)) return true;
        return false;
    }

    // words[start]부터 낱말 여러 개를 이어 key가 되는지(첫 낱말은 통째로, 마지막 낱말은 앞부분 + 조사·어미)
    private boolean spansWords(String key, int start) {
        String first = words[start];
        if (first.length() >= key.length() || !key.startsWith(first)) return false;
        String rest = key.substring(first.length());
        for (int i = start + 1; i < words.length; i++) {
            String word = words[i];
            if (rest.equals(word)) return true;
            if (rest.startsWith(word)) { rest = rest.substring(word.length()); continue; }
            if (word.startsWith(rest)) {
                String tail = word.substring(rest.length());
                return PARTICLES.stream().anyMatch(tail::startsWith);
            }
            return false;
        }
        return false;
    }

    private static String base(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.KOREAN);
    }

    private static String dropDigitsInWords(String value) {
        return DIGITS_IN_HANGUL.matcher(value).replaceAll("");
    }

    // "씨 발 놈" → "씨발놈", "다시 발견" → 그대로
    private static String joinSingleCharacterWords(String spaced) {
        if (spaced.isEmpty()) return spaced;
        StringBuilder out = new StringBuilder();
        boolean previousSingle = false;
        for (String word : spaced.split(" ")) {
            boolean single = word.codePointCount(0, word.length()) == 1;
            if (out.length() > 0 && !(single && previousSingle)) out.append(' ');
            out.append(word);
            previousSingle = single;
        }
        return out.toString();
    }
}
