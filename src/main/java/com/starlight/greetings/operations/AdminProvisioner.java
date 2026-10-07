package com.starlight.greetings.operations;

import com.starlight.greetings.domain.auth.PasswordHasher;
import com.starlight.greetings.domain.auth.SignupValidator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "starlight.provision-admin", havingValue = "true")
public class AdminProvisioner implements ApplicationRunner {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final JdbcTemplate jdbc; private final String key; private final String nickname; private final String password;
    private final SecureRandom random = new SecureRandom();
    public AdminProvisioner(JdbcTemplate jdbc, @Value("${ADMIN_TOTP_KEY:}") String key, @Value("${ADMIN_NICKNAME:}") String nickname, @Value("${ADMIN_PASSWORD:}") String password) { this.jdbc=jdbc; this.key=key; this.nickname=nickname; this.password=password; }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (key.isBlank() || nickname.isBlank() || password.isBlank()) throw new IllegalStateException("ADMIN_TOTP_KEY, ADMIN_NICKNAME, ADMIN_PASSWORD are required");
        var name = new SignupValidator().nickname(nickname); String validPassword = new SignupValidator().password(password); byte[] rawKey = Base64.getDecoder().decode(key); if (rawKey.length != 32) throw new IllegalStateException("ADMIN_TOTP_KEY must decode to 32 bytes");
        String secret = secret(); String encrypted = encrypt(secret, rawKey); PasswordHasher hashes = new PasswordHasher();
        jdbc.update("insert into users(id,nickname,nickname_key,password_hash,recovery_code_hash,role,status,totp_secret_ciphertext) values(?::uuid,?,?,?,?, 'admin','active',?)", UUID.randomUUID().toString(), name.display(), name.key(), hashes.hash(validPassword), hashes.hash(token()), encrypted);
        System.out.println("TOTP secret (save once): " + secret);
    }
    private String secret() { byte[] input = new byte[20]; random.nextBytes(input); int bits=0,value=0; StringBuilder out=new StringBuilder(); for(byte b:input){value=(value<<8)|(b&255);bits+=8;while(bits>=5){out.append(ALPHABET.charAt((value>>(bits-5))&31));bits-=5;}}if(bits>0)out.append(ALPHABET.charAt((value<<(5-bits))&31));return out.toString(); }
    private String encrypt(String secret, byte[] rawKey) throws Exception { byte[] iv=new byte[12];random.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(rawKey,"AES"),new GCMParameterSpec(128,iv));byte[] result=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));int tagOffset=result.length-16;byte[] ciphertext=java.util.Arrays.copyOf(result,tagOffset),tag=java.util.Arrays.copyOfRange(result,tagOffset,result.length);return Base64.getUrlEncoder().withoutPadding().encodeToString(iv)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(tag)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext); }
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
