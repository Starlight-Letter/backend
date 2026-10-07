package com.starlight.greetings.application;

import com.starlight.greetings.domain.auth.PasswordHasher;
import com.starlight.greetings.domain.auth.SignupValidator;
import com.starlight.greetings.web.ApiException;
import com.starlight.greetings.web.SignupResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcLoginService implements LoginService {
    private final JdbcTemplate jdbc; private final SignupValidator validator = new SignupValidator(); private final PasswordHasher hasher = new PasswordHasher(); private final SecureRandom random = new SecureRandom();
    public JdbcLoginService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional public SignupResult login(String nicknameInput, String passwordInput) {
        var nickname = validator.nickname(nicknameInput); String password = validator.password(passwordInput);
        var users = jdbc.query("select id,nickname,password_hash from users where nickname_key=? and status='active' and role='member'", (row, i) -> new String[]{row.getString("id"),row.getString("nickname"),row.getString("password_hash")}, nickname.key());
        if (users.isEmpty() || !hasher.matches(users.get(0)[2], password)) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "닉네임 또는 비밀번호를 확인해 주세요.");
        String session=token(), csrf=token();
        jdbc.update("insert into sessions (id,user_id,token_hash,csrf_hash,csrf_token,expires_at) values (?,?,?,?,?,now()+interval '30 days')", UUID.randomUUID().toString(),users.get(0)[0],digest(session),digest(csrf),csrf);
        return new SignupResult(users.get(0)[0],users.get(0)[1],null,csrf,session);
    }
    private String token(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private String digest(String s){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
