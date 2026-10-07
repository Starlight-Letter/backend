package com.starlight.greetings.domain.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    @Test void usesNodeCompatibleArgon2idHashes() {
        PasswordHasher hasher = new PasswordHasher();
        String encoded = hasher.hash("password1");
        assertTrue(encoded.startsWith("$argon2id$"));
        assertTrue(hasher.matches(encoded, "password1"));
    }
}
