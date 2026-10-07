package com.starlight.greetings.domain.auth;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;

public class PasswordHasher {
    private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
    public String hash(String value) { return argon2.hash(2, 19_456, 1, value.toCharArray()); }
    public boolean matches(String encoded, String value) { return argon2.verify(encoded, value.toCharArray()); }
}
