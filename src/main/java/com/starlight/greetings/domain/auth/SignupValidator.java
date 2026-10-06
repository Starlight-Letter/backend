package com.starlight.greetings.domain.auth;

import java.text.Normalizer;

public class SignupValidator {
    public Nickname nickname(String value) {
        if (value == null) throw new IllegalArgumentException("닉네임을 확인해 주세요.");
        String display = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        int length = display.codePointCount(0, display.length());
        if (length < 2 || length > 20 || display.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("닉네임은 2~20자로 입력해 주세요.");
        }
        return new Nickname(display, display.toLowerCase(java.util.Locale.forLanguageTag("ko-KR")));
    }

    public String password(String value) {
        if (value == null || value.length() < 8 || value.length() > 128) {
            throw new IllegalArgumentException("비밀번호는 8~128자로 입력해 주세요.");
        }
        return value;
    }

    public record Nickname(String display, String key) { }
}
