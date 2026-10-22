package com.starlight.greetings.domain.auth;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public class PasswordHasher {
    // Argon2는 해시 하나에 19MB의 네이티브 메모리(JVM 힙 밖이라 -Xmx로 막히지 않음)와 CPU를 쓴다.
    // 로그인·가입이 몰려 동시에 수십 건이 돌면 순간 메모리가 수백 MB로 치솟아 작은 서버(2GB)는 스왑·OOM에 빠진다.
    // CPU 수보다 많이 동시에 돌려도 빨라지지 않으므로, CPU 수만큼만 계산하고 나머지는 줄을 세운다(모든 인스턴스가 공유).
    private static final Semaphore SLOTS = new Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors()), true);
    private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
    public String hash(String value) { return limited(() -> argon2.hash(2, 19_456, 1, value.toCharArray())); }
    public boolean matches(String encoded, String value) { return limited(() -> argon2.verify(encoded, value.toCharArray())); }
    private static <T> T limited(Supplier<T> work) {
        SLOTS.acquireUninterruptibly();
        try { return work.get(); } finally { SLOTS.release(); }
    }
}
