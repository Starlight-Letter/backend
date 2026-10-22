package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starlight.greetings.application.JdbcAchievementService;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// 별자리 업적: 조건을 채우면 한 번만 '새로 켜짐'으로 알리고, 이후 데이터가 줄어도 꺼지지 않는다.
// 자기 편지에 남긴 위로·보관은 세지 않고, 날짜는 KST 기준으로 센다. 특별한 유리병은 해금한 사람만 쓴다.
class AchievementIntegrationTest {
 @Test void unlocksStarsOnceAndKeepsThemAndGatesSpecialBottles() throws Exception {
  String databaseUrl = System.getenv("TEST_DATABASE_URL");
  if (databaseUrl == null) return;
  URI uri = URI.create(databaseUrl); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  String schema = "achievement_" + UUID.randomUUID().toString().replace('-', '_');
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + schema);
   Flyway.configure().dataSource(base, auth[0], auth[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
   DriverManagerDataSource dataSource = new DriverManagerDataSource(base + "?currentSchema=" + schema, auth[0], auth[1]);
   JdbcTemplate jdbc = new JdbcTemplate(dataSource); JdbcAchievementService service = new JdbcAchievementService(jdbc);
   String me = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
   jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,'나','나','hash','recovery','member','active'),(?::uuid,'너','너','hash','recovery','member','active')", me, other);
   for (String bridge : List.of("bongnim", "sillim", "sillim2")) jdbc.update("insert into questions (id,bridge_id,body,effective_at,status) values (gen_random_uuid(),?,'오늘 어땠나요?',now()-interval '1 day','published')", bridge);

   Map<String, Object> start = service.evaluate(me);
   assertEquals(List.of(), start.get("newlyUnlocked"));
   assertEquals(false, start.get("completed"));
   assertFalse(service.canUseTheme(me, 5));
   assertTrue(service.canUseTheme(me, 2));
   assertFalse(service.canUseTheme(me, 4)); // 기본 병은 2종만 남겼다

   // KST로 10월 10일 23:30, 10월 11일 00:30 → 서로 다른 이틀 밤
   String first = letter(jdbc, me, "bongnim", "2026-10-10T14:30:00Z");
   assertEquals(List.of("first_letter"), service.evaluate(me).get("newlyUnlocked"));
   assertEquals(List.of(), service.evaluate(me).get("newlyUnlocked")); // 이미 켜진 별은 다시 알리지 않는다
   letter(jdbc, me, "sillim", "2026-10-10T15:30:00Z");
   assertEquals(List.of("two_nights"), service.evaluate(me).get("newlyUnlocked"));
   assertFalse(service.canUseTheme(me, 5));
   letter(jdbc, me, "sillim2", "2026-10-10T15:40:00Z");
   assertTrue(service.canUseTheme(me, 5)); // 아직 화면을 열지 않았어도 테마 검사에서 기록된다
   assertEquals(List.of(), service.evaluate(me).get("newlyUnlocked"));

   // 내가 내 편지에 남긴 위로·보관은 세지 않는다
   react(jdbc, me, first); save(jdbc, me, first);
   assertEquals(List.of(), service.evaluate(me).get("newlyUnlocked"));
   react(jdbc, other, first); save(jdbc, other, first);
   assertEquals(List.of("first_comfort", "kept_once"), service.evaluate(me).get("newlyUnlocked"));

   for (int i = 0; i < 3; i++) { String theirs = letter(jdbc, other, "bongnim", "2026-10-12T03:00:00Z"); react(jdbc, me, theirs); save(jdbc, me, theirs); }
   Map<String, Object> done = service.evaluate(me);
   assertEquals(List.of("kind_reader", "collector", "constellation"), done.get("newlyUnlocked"));
   assertEquals(true, done.get("completed"));
   assertTrue(service.canUseTheme(me, 6));
   assertFalse(service.canUseTheme(other, 6));

   // 위로를 모두 취소해도 켜진 별은 그대로
   jdbc.update("update reactions set active=false");
   @SuppressWarnings("unchecked") List<Map<String, Object>> after = (List<Map<String, Object>>) service.evaluate(me).get("achievements");
   assertEquals(7, after.size());
   assertTrue(after.stream().allMatch(item -> Boolean.TRUE.equals(item.get("unlocked"))));
   assertEquals(0, after.stream().filter(item -> item.get("code").equals("kind_reader")).findFirst().orElseThrow().get("progress"));
  } finally {
   try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) { statement.execute("drop schema if exists " + schema + " cascade"); }
  }
 }

 private static String letter(JdbcTemplate jdbc, String author, String bridge, String at) {
  String id = UUID.randomUUID().toString();
  jdbc.update("insert into letters (id,bridge_id,question_id,author_user_id,display_nickname,body,theme,is_seed,status,created_at) select ?::uuid,?,q.id,?::uuid,'익명의 별','오늘도 잘 버텼어요.',1,false,'published',?::timestamptz from questions q where q.bridge_id=?", id, bridge, author, at, bridge);
  return id;
 }
 private static void react(JdbcTemplate jdbc, String user, String letter) {
  jdbc.update("insert into reactions (id,letter_id,user_id,active,activated_at) values (gen_random_uuid(),?::uuid,?::uuid,true,now())", letter, user);
 }
 private static void save(JdbcTemplate jdbc, String user, String letter) {
  jdbc.update("insert into saves (user_id,letter_id) values (?::uuid,?::uuid)", user, letter);
 }
}
