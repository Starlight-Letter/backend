package com.starlight.greetings.domain.moderation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModerationTextTest {
    private static boolean has(String text, String phrase) { return ModerationText.of(text).contains(ModerationText.key(phrase)); }

    @Test void catchesObfuscatedSpellings() {
        assertTrue(has("씨.발", "씨발"));
        assertTrue(has("씨*발 진짜", "씨발"));
        assertTrue(has("씨1발", "씨발"));
        assertTrue(has("씨 1 발", "씨발"));
        assertTrue(has("씨 발", "씨발"));
        assertTrue(has("ㅅ ㅂ", "ㅅㅂ"));
        assertTrue(has("개 새 끼", "개새끼"));
        assertTrue(has("오픈 채팅으로 와요", "오픈채팅"));
        assertTrue(has("오.픈.채.팅", "오픈채팅"));
        assertTrue(has("미친 놈", "미친놈"));
        assertTrue(has("카톡 아이디 알려줘", "카톡아이디"));
        assertTrue(has("bit.ly/abc 들어와", "bit.ly"));
        assertTrue(has("ㅅㅂ", "ㅅㅂ"));
        assertTrue(has("ＦＵＣＫ", "fuck"));
    }

    @Test void doesNotJoinOrdinaryWordsAcrossSpaces() {
        assertFalse(has("다시 발견한 마음이에요", "시발"));
        assertFalse(has("다시 발걸음을 옮겨요", "시발"));
        assertFalse(has("솜씨 발휘했어요", "씨발"));
        assertFalse(has("병 신경 쓰지 마요", "병신"));
        assertFalse(has("오늘도 수고했어요", "씨발"));
        assertFalse(has("push it", "shit"));
        assertFalse(has("또 라이브 보러 가요", "또라이"));
        assertFalse(has("카톡 아이디어 회의", "카톡아이디"));
        assertFalse(has("새로운 시발점", "시발"));
    }

    @Test void allowsParticlesAfterSpacedPhrases() {
        assertTrue(has("오픈 채팅으로 와요", "오픈채팅"));
        assertTrue(has("미친 놈아", "미친놈"));
        assertTrue(has("카톡 아이디는 비밀", "카톡아이디"));
    }

    @Test void ignoresRulesWithNothingLeftAfterCleanup() {
        assertEquals("", ModerationText.key(" !! "));
        assertFalse(ModerationText.of("아무 문장").contains(""));
    }
}
