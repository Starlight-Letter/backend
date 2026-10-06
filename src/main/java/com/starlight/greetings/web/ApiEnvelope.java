package com.starlight.greetings.web;

public record ApiEnvelope<T>(T data) {
    public static <T> ApiEnvelope<T> of(T data) {
        return new ApiEnvelope<>(data);
    }
}
