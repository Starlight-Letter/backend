package com.starlight.greetings.domain.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SignupValidatorTest {
    @Test void normalizesNicknameAndRejectsInvalidCredentials() {
        SignupValidator validator = new SignupValidator();
        assertEquals("별빛 안부", validator.nickname(" 별빛 안부 ").display());
        assertThrows(IllegalArgumentException.class, () -> validator.nickname("a"));
        assertThrows(IllegalArgumentException.class, () -> validator.password("short"));
    }
}
