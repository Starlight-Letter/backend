package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starlight.greetings.application.JdbcEngagementService;
import com.starlight.greetings.web.ApiException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class EngagementIntegrationTest {
 @Test void togglesOneReactionAndKeepsHiddenSavedLetterAsPlaceholder() throws Exception {
  String databaseUrl = System.getenv("TEST_DATABASE_URL");
  if (databaseUrl == null) return;
  URI uri = URI.create(databaseUrl); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  String schema = "engagement_" + UUID.randomUUID().toString().replace('-', '_');
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + schema);
   Flyway.configure().dataSource(base, auth[0], auth[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
   DriverManagerDataSource dataSource = new DriverManagerDataSource(base + "?currentSchema=" + schema, auth[0], auth[1]);
   JdbcTemplate jdbc = new JdbcTemplate(dataSource); JdbcEngagementService service = new JdbcEngagementService(jdbc);
   String user = UUID.randomUUID().toString(), letter = UUID.randomUUID().toString();
   jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,'별빛이','별빛이','hash','recovery','member','active')", user);
   jdbc.update("insert into letters (id,bridge_id,display_nickname,body,theme,is_seed,status) values (?::uuid,'bongnim','별빛이','오늘도 잘 버텼어요.',2,true,'published')", letter);
   assertEquals(1, service.react(letter, user, true).get("reactionCount"));
   assertEquals(1, service.react(letter, user, true).get("reactionCount"));
   assertFalse((Boolean) service.react(letter, user, false).get("active"));
   assertTrue((Boolean) service.save(letter, user, true).get("saved"));
   jdbc.update("update letters set status='hidden', hidden_at=now() where id=?::uuid", letter);
   Map<String,Object> saved = service.saved(user).get(0);
   assertTrue((Boolean) saved.get("unavailable"));
   assertEquals("지금은 볼 수 없는 편지", saved.get("message"));
  } finally {
   try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) { statement.execute("drop schema if exists " + schema + " cascade"); }
  }
 }

 // 서랍장 순위는 짧게 캐시하지만, 숨김 처리된 편지는 캐시와 상관없이 바로 빠지고 순위 번호도 다시 매겨져야 한다.
 @Test void ranksBestByReactionsAndDropsHiddenLetterEvenWhenCached() throws Exception {
  String databaseUrl = System.getenv("TEST_DATABASE_URL");
  if (databaseUrl == null) return;
  URI uri = URI.create(databaseUrl); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  String schema = "best_" + UUID.randomUUID().toString().replace('-', '_');
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + schema);
   Flyway.configure().dataSource(base, auth[0], auth[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
   DriverManagerDataSource dataSource = new DriverManagerDataSource(base + "?currentSchema=" + schema, auth[0], auth[1]);
   JdbcTemplate jdbc = new JdbcTemplate(dataSource); JdbcEngagementService service = new JdbcEngagementService(jdbc);
   List<String> users = IntStream.range(0, 4).mapToObj(i -> UUID.randomUUID().toString()).toList();
   for (int i = 0; i < users.size(); i++) jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,?,?,'hash','recovery','member','active')", users.get(i), "별빛" + i, "별빛" + i);
   // 위로 수: hidden 4, sillim 3, first 2, second 1
   String hidden = letter(jdbc, "bongnim"), sillim = letter(jdbc, "sillim"), first = letter(jdbc, "bongnim"), second = letter(jdbc, "bongnim");
   for (int i = 0; i < 4; i++) service.react(hidden, users.get(i), true);
   for (int i = 0; i < 3; i++) service.react(sillim, users.get(i), true);
   for (int i = 0; i < 2; i++) service.react(first, users.get(i), true);
   service.react(second, users.get(0), true);

   assertEquals(List.of(hidden, sillim, first, second), ids(service.best("all", null, "week")));
   assertEquals(4, service.best("all", null, "week").get(0).get("reactionCount"));
   assertEquals(List.of(hidden, first, second), ids(service.best("bridge", "bongnim", "month")));

   jdbc.update("update letters set status='hidden', hidden_at=now() where id=?::uuid", hidden);
   List<Map<String, Object>> best = service.best("all", null, "week");
   assertEquals(List.of(sillim, first, second), ids(best));
   assertEquals(List.of(1, 2, 3), best.stream().map(item -> item.get("rank")).toList());
   assertEquals(List.of(first, second), ids(service.best("bridge", "bongnim", "month")));
  } finally {
   try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) { statement.execute("drop schema if exists " + schema + " cascade"); }
  }
 }

 // 내가 띄운 편지 목록과 직접 지우기: 남의 편지·검토 중인 편지는 못 지우고, 지우면 목록·보관·하루 작성 수에서 빠진다.
 @Test void listsAndDeletesMyOwnLetters() throws Exception {
  String databaseUrl = System.getenv("TEST_DATABASE_URL");
  if (databaseUrl == null) return;
  URI uri = URI.create(databaseUrl); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  String schema = "mine_" + UUID.randomUUID().toString().replace('-', '_');
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + schema);
   Flyway.configure().dataSource(base, auth[0], auth[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
   DriverManagerDataSource dataSource = new DriverManagerDataSource(base + "?currentSchema=" + schema, auth[0], auth[1]);
   JdbcTemplate jdbc = new JdbcTemplate(dataSource); JdbcEngagementService service = new JdbcEngagementService(jdbc);
   String me = UUID.randomUUID().toString(), other = UUID.randomUUID().toString(), question = UUID.randomUUID().toString();
   jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,'나','나','hash','recovery','member','active'),(?::uuid,'너','너','hash','recovery','member','active')", me, other);
   jdbc.update("insert into questions (id,bridge_id,body,effective_at,status) values (?::uuid,'bongnim','오늘 어땠나요?',now()-interval '1 day','published')", question);
   String mine = memberLetter(jdbc, me, question), hiddenMine = memberLetter(jdbc, me, question), theirs = memberLetter(jdbc, other, question);
   jdbc.update("update letters set status='hidden', hidden_at=now() where id=?::uuid", hiddenMine);
   service.react(mine, other, true); service.react(mine, me, true); service.save(mine, other, true);

   List<Map<String, Object>> letters = service.myLetters(me);
   assertEquals(List.of(hiddenMine, mine), ids(letters)); // 최신순
   assertEquals("hidden", letters.get(0).get("status"));
   assertEquals("오늘도 잘 버텼어요.", letters.get(0).get("preview")); // 숨겨져도 본인에게는 본문을 보여준다
   assertEquals(1, letters.get(1).get("reactionCount")); // 내가 내 편지에 남긴 위로는 빼고 센다
   assertEquals(1, letters.get(1).get("keptCount"));
   assertEquals("오늘 어땠나요?", letters.get(1).get("questionBody"));

   assertEquals(404, assertThrows(ApiException.class, () -> service.deleteMyLetter(theirs, me)).status().value());
   assertEquals("LETTER_UNDER_REVIEW", assertThrows(ApiException.class, () -> service.deleteMyLetter(hiddenMine, me)).code());

   assertEquals(Map.of("deleted", true), service.deleteMyLetter(mine, me));
   assertEquals(List.of(hiddenMine), ids(service.myLetters(me)));
   assertEquals(0, jdbc.queryForObject("select count(*)::int from saves where letter_id=?::uuid", Integer.class, mine));
   assertEquals(1, jdbc.queryForObject("select count(*)::int from letters where author_user_id=?::uuid", Integer.class, me)); // 하루 작성 수는 작성자 연결로 센다
   assertEquals("작성자 삭제", jdbc.queryForObject("select reason from deletion_events where letter_id=?::uuid", String.class, mine));
   assertEquals(404, assertThrows(ApiException.class, () -> service.deleteMyLetter(mine, me)).status().value());
  } finally {
   try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) { statement.execute("drop schema if exists " + schema + " cascade"); }
  }
 }

 private static String memberLetter(JdbcTemplate jdbc, String author, String question) {
  String id = UUID.randomUUID().toString();
  jdbc.update("insert into letters (id,bridge_id,question_id,author_user_id,display_nickname,body,theme,is_seed,status,created_at) values (?::uuid,'bongnim',?::uuid,?::uuid,'익명의 별','오늘도 잘 버텼어요.',1,false,'published',clock_timestamp())", id, question, author);
  return id;
 }

 private static String letter(JdbcTemplate jdbc, String bridge) {
  String id = UUID.randomUUID().toString();
  jdbc.update("insert into letters (id,bridge_id,display_nickname,body,theme,is_seed,status) values (?::uuid,?,'별빛안부','오늘도 잘 버텼어요.',1,true,'published')", id, bridge);
  return id;
 }

 private static List<Object> ids(List<Map<String, Object>> best) { return best.stream().map(item -> item.get("id")).toList(); }
}
