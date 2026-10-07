package com.starlight.greetings.web;
public record SessionView(String id, String nickname, String role, String csrfToken) { }
