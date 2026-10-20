package com.starlight.greetings.web;

/**
 * currentQuestionLetterCount: 이번 주 질문에 답한 공개 회원 편지 수(시작 편지 제외).
 * 다리 단건 조회에서만 채우고, 목록 조회에서는 null이다.
 */
public record BridgeView(String id, String name, int displayOrder, BridgeQuestion currentQuestion, Integer currentQuestionLetterCount) {
    public BridgeView(String id, String name, int displayOrder, BridgeQuestion currentQuestion) {
        this(id, name, displayOrder, currentQuestion, null);
    }
}
