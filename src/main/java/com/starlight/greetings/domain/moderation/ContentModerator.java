package com.starlight.greetings.domain.moderation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ContentModerator {
    private static final Pattern PROFANITY = Pattern.compile("씨발|시발|좆|개새끼|병신|ㅅㅂ", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PHONE = Pattern.compile("\\b01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}\\b");
    private static final Pattern AD = Pattern.compile("https?://|www\\.|\\b[a-z0-9-]+\\.(?:com|net|org|kr|co\\.kr)\\b|(광고|홍보|판매|구매|특가|할인)\\s*(문의|링크|코드|이벤트)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    public String category(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).replace("\u200B", "").replace("\uFEFF", "");
        if (normalized.trim().codePointCount(0, normalized.trim().length()) < 1 || normalized.trim().codePointCount(0, normalized.trim().length()) > 150) return "length";
        String searchable = normalized.replace("\u200C", "").replace("\u200D", "").toLowerCase(Locale.KOREAN);
        if (PROFANITY.matcher(searchable).find()) return "profanity";
        if (EMAIL.matcher(normalized).find() || PHONE.matcher(normalized).find()) return "personal_info";
        if (AD.matcher(normalized).find()) return "advertising";
        return null;
    }
}
