package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.starlight.greetings.application.JdbcAdminLoginService;
import com.starlight.greetings.domain.auth.PasswordHasher;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminLoginIntegrationTest {
    @Test
    void logsInWithASecretEncryptedByTheProvisioningFormat() throws Exception {
        String databaseUrl = System.getenv("TEST_DATABASE_URL");
        if (databaseUrl == null) return;
        URI uri = URI.create(databaseUrl);
        String[] credentials = uri.getUserInfo().split(":", 2);
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
        String schema = "admin_login_" + UUID.randomUUID().toString().replace('-', '_');
        try (Connection connection = DriverManager.getConnection(jdbcUrl, credentials[0], credentials[1]); Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
            Flyway.configure().dataSource(jdbcUrl, credentials[0], credentials[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl + "?currentSchema=" + schema, credentials[0], credentials[1]));
            byte[] rawKey = new byte[32];
            String key = Base64.getEncoder().encodeToString(rawKey);
            String secret = "JBSWY3DPEHPK3PXP" + "A".repeat(16);
            jdbc.update("insert into users(id,nickname,nickname_key,password_hash,recovery_code_hash,role,status,totp_secret_ciphertext) values (?::uuid,'운영점검','운영점검',?,'recovery','admin','active',?)", UUID.randomUUID().toString(), new PasswordHasher().hash("admin-check-pass-1234"), encryptLikeProvisioner(secret, rawKey));

            var result = new JdbcAdminLoginService(jdbc, key).login("운영점검", "admin-check-pass-1234", totp(secret));

            assertNotNull(result.sessionToken());
        } finally {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, credentials[0], credentials[1]); Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private String encryptLikeProvisioner(String secret, byte[] rawKey) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rawKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        int tagOffset = encrypted.length - 16;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(encrypted, tagOffset, encrypted.length)) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(encrypted, tagOffset));
    }

    private String totp(String secret) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int bits = 0, value = 0;
        java.io.ByteArrayOutputStream decoded = new java.io.ByteArrayOutputStream();
        for (char character : secret.toCharArray()) {
            value = (value << 5) | alphabet.indexOf(character);
            bits += 5;
            if (bits >= 8) { decoded.write((value >> (bits - 8)) & 255); bits -= 8; }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(decoded.toByteArray(), "HmacSHA1"));
        ByteBuffer counter = ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30000);
        byte[] hash = mac.doFinal(counter.array());
        int offset = hash[hash.length - 1] & 15;
        int number = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16) | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
        return String.format("%06d", number % 1_000_000);
    }
}
