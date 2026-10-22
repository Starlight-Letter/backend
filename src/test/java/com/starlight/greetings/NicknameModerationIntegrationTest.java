package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starlight.greetings.application.JdbcAdminOperationsService;
import com.starlight.greetings.application.JdbcReportService;
import com.starlight.greetings.application.ModerationRules;
import com.starlight.greetings.application.NicknamePolicy;
import com.starlight.greetings.domain.moderation.ContentModerator;
import com.starlight.greetings.web.ApiException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// 닉네임 관리: 가입 때 사칭·욕설·연락처·광고·운영자 검사 규칙을 막고(규칙은 캐시), 운영자는 닉네임을 가릴 수 있고, 회원은 닉네임으로 신고할 수 있다.
class NicknameModerationIntegrationTest {
 @Test void blocksBadNicknamesMasksThemAndAcceptsNicknameReports() throws Exception {
  String databaseUrl = System.getenv("TEST_DATABASE_URL");
  if (databaseUrl == null) return;
  URI uri = URI.create(databaseUrl); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  String schema = "nickname_" + UUID.randomUUID().toString().replace('-', '_');
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + schema);
   Flyway.configure().dataSource(base, auth[0], auth[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
   DriverManagerDataSource dataSource = new DriverManagerDataSource(base + "?currentSchema=" + schema, auth[0], auth[1]);
   JdbcTemplate jdbc = new JdbcTemplate(dataSource);
   ModerationRules rules = new ModerationRules(jdbc);
   NicknamePolicy policy = new NicknamePolicy(new ContentModerator(), rules);
   JdbcAdminOperationsService admin = new JdbcAdminOperationsService(jdbc, rules);
   String adminId = UUID.randomUUID().toString(), member = UUID.randomUUID().toString(), reporter = UUID.randomUUID().toString();
   jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status,totp_secret_ciphertext) values (?::uuid,'운영자','운영자','h','r','admin','active','x')", adminId);
   jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,'나쁜이름','나쁜이름','h','r','member','active'),(?::uuid,'신고자','신고자','h','r','member','active')", member, reporter);

   // 1) 사칭·기본 검사: 띄어쓰기·기호를 끼워도 잡는다
   assertEquals("reserved", policy.problem("별빛안부"));
   assertEquals("reserved", policy.problem("익명의 별빛"));
   assertEquals("reserved", policy.problem("운 영 자"));
   assertEquals("reserved", policy.problem("Admin_01"));
   assertNull(policy.problem("badminton러버"));
   assertEquals("profanity", policy.problem("시발놈"));
   assertEquals("personal_info", policy.problem("010-1234-5678"));
   assertEquals("advertising", policy.problem("광고문의주세요"));
   assertNull(policy.problem("달빛산책"));
   assertEquals("NICKNAME_NOT_ALLOWED", assertThrows(ApiException.class, () -> policy.require("관리자")).code());

   // 2) 운영자 검사 규칙: 처음 한 번 읽어 캐시하고, 운영자가 규칙을 추가하면 바로 반영된다
   assertNull(policy.problem("멍청이별"));
   admin.createRule(Map.of("category", "profanity", "phrase", "멍청이", "reason", "닉네임 테스트"), adminId);
   assertEquals("rule", policy.problem("멍청이별"));
   assertEquals("rule", policy.problem("멍 청 이"));
   jdbc.update("insert into moderation_rules (id,category,phrase,created_by) values (gen_random_uuid(),'profanity','바보',?::uuid)", adminId);
   assertNull(policy.problem("바보별")); // 운영자 화면을 거치지 않은 직접 수정은 캐시가 끝날 때(1분)까지 반영되지 않는다
   rules.invalidate();
   assertEquals("rule", policy.problem("바보별"));

   // 3) 닉네임 신고: 회원 서명 편지만 가능
   String named = UUID.randomUUID().toString(), anon = UUID.randomUUID().toString(), seed = UUID.randomUUID().toString();
   String question = UUID.randomUUID().toString();
   jdbc.update("insert into questions (id,bridge_id,body,effective_at,status) values (?::uuid,'bongnim','오늘 어땠나요?',now()-interval '1 day','published')", question);
   jdbc.update("insert into letters (id,bridge_id,question_id,author_user_id,display_nickname,body,theme,is_seed,status) values (?::uuid,'bongnim',?::uuid,?::uuid,'나쁜이름','안녕하세요',1,false,'published'),(?::uuid,'bongnim',?::uuid,?::uuid,'익명의 별빛','익명 편지',1,false,'published')", named, question, member, anon, question, member);
   jdbc.update("insert into letters (id,bridge_id,display_nickname,body,theme,is_seed,status) values (?::uuid,'bongnim','별빛안부','시작 편지',1,true,'published')", seed);
   JdbcReportService reports = new JdbcReportService(jdbc);
   assertEquals(1, reports.report(named, reporter, "nickname").get("reportCount"));
   assertEquals("INVALID_REASON", assertThrows(ApiException.class, () -> reports.report(anon, reporter, "nickname")).code());
   assertEquals("INVALID_REASON", assertThrows(ApiException.class, () -> reports.report(seed, reporter, "nickname")).code());

   // 4) 닉네임 가리기: 닉네임을 '별빛회원+숫자'로, 그 닉네임 서명만 '익명의 별빛'으로(본문·익명 편지는 그대로)
   Map<String, Object> reset = admin.resetNickname(member, "부적절한 닉네임 신고", adminId);
   String next = (String) reset.get("nickname");
   assertTrue(next.matches("별빛회원\\d{6}"));
   assertEquals(1, reset.get("maskedLetters"));
   assertEquals(next, jdbc.queryForObject("select nickname from users where id=?::uuid", String.class, member));
   assertEquals("익명의 별빛", jdbc.queryForObject("select display_nickname from letters where id=?::uuid", String.class, named));
   assertEquals("안녕하세요", jdbc.queryForObject("select body from letters where id=?::uuid", String.class, named));
   assertEquals(1, jdbc.queryForObject("select count(*)::int from admin_actions where action='user.reset_nickname' and target_id=?", Integer.class, member));
  } finally {
   try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) { statement.execute("drop schema if exists " + schema + " cascade"); }
  }
 }
}
