package com.starlight.greetings.domain.moderation;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ContentModerator {
    /** 걸린 범주(length·profanity·personal_info·advertising)와, 안내에 보여줄 걸린 표현(없으면 null) */
    public record Result(String category, String matched) {}

    // 기본 욕설. 띄어 쓰거나 기호·숫자를 끼운 표기(씨 발, 씨.발, 씨1발)까지 ModerationText로 잡는다
    private static final List<String> PROFANITY = List.of("씨발", "시발", "좆", "개새끼", "병신", "ㅅㅂ");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PHONE = Pattern.compile("01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}");
    private static final Pattern AD = Pattern.compile("https?://|www\\.|\\b[a-z0-9-]+\\.(?:com|net|org|kr|co\\.kr)\\b|(광고|홍보|판매|구매|특가|할인)\\s*(문의|링크|코드|이벤트)|(광고|홍보|판매|구매).{0,20}(특가|할인)|(특가|할인).{0,20}(광고|홍보|판매|구매)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public String category(String input) {
        Result result = check(input);
        return result == null ? null : result.category();
    }

    public Result check(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).replace("​", "").replace("﻿", "");
        if (normalized.trim().codePointCount(0, normalized.trim().length()) < 1 || normalized.trim().codePointCount(0, normalized.trim().length()) > 150) return new Result("length", null);
        String searchable = normalized.replace("‌", "").replace("‍", "").toLowerCase(Locale.KOREAN);
        String compact = searchable.replaceAll("[\\s\\p{Z}]", "");
        // 기호까지 지운 문자열: 010.1234.5678, 광고.문의 같은 표기용(이메일·주소는 기호가 있어야 하므로 compact로 본다)
        String stripped = ModerationText.stripped(normalized);
        ModerationText text = ModerationText.of(normalized);
        // 비교는 같은 정규화를 거친 키로 한다(NFKC는 ㅅ·ㅂ 같은 자음을 다른 코드로 바꾼다)
        for (String word : PROFANITY) if (text.contains(ModerationText.key(word))) return new Result("profanity", word);
        if (EMAIL.matcher(compact).find() || PHONE.matcher(compact).find() || PHONE.matcher(stripped).find()) return new Result("personal_info", null);
        if (AD.matcher(compact).find() || AD.matcher(stripped).find()) return new Result("advertising", null);
        return null;
    }
}
