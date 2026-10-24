package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starlight.greetings.application.LetterQueryService;
import com.starlight.greetings.web.LetterPreview;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// 강물 가중 추출: 이미 본 편지는 빼지 않고 덜 뽑히게만 한다. 시작 편지 섞는 규칙은 그대로이고, 이상한 seen 값은 버린다.
class RiverWeightingIntegrationTest {
 private static final int ROUNDS = 600;

 @Test void prefersUnseenLettersWithoutHidingSeenOnes() throws Exception {
  String databaseUrl = System.getenv("TEST_DATABASE_URL");
  if (databaseUrl == null) return;
  URI uri = URI.create(databaseUrl); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  String schema = "river_" + UUID.randomUUID().toString().replace('-', '_');
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + schema);
   Flyway.configure().dataSource(base, auth[0], auth[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
   JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(base + "?currentSchema=" + schema, auth[0], auth[1]));
   LetterQueryService service = new LetterQueryService(jdbc);
   String author = UUID.randomUUID().toString();
   jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,'나','나','hash','recovery','member','active')", author);
   jdbc.update("insert into questions (id,bridge_id,body,effective_at,status) values (gen_random_uuid(),'bongnim','오늘 어땠나요?',now()-interval '1 day','published')");

   // 초반처럼 회원 편지 12통(그중 4통은 이미 봄, 안 본 8통으로 4칸을 다 채울 수 있음) + 시작 편지 3통(1통은 이미 봄)
   List<String> members = new ArrayList<>(), seeds = new ArrayList<>();
   for (int i = 0; i < 12; i++) members.add(member(jdbc, author));
   for (int i = 0; i < 3; i++) seeds.add(seed(jdbc));
   List<String> seen = new ArrayList<>(members.subList(0, 4)); seen.add(seeds.get(0));
   Set<String> seenSet = new HashSet<>(seen);

   // 가중치 없이(seen 없음) 부르면 지금과 같다: 회원 4통 + 시작 1통
   List<LetterPreview> plain = service.river("bongnim");
   assertEquals(5, plain.size());
   assertEquals(1, plain.stream().filter(LetterPreview::isSeed).count());

   Map<String, Integer> picks = new HashMap<>();
   int batchesWithSeenMember = 0;
   for (int round = 0; round < ROUNDS; round++) {
    List<LetterPreview> batch = service.river("bongnim", seen);
    assertEquals(5, batch.size());
    assertEquals(1, batch.stream().filter(LetterPreview::isSeed).count()); // 시작 편지는 최대 1통 규칙 유지
    assertEquals(5, batch.stream().map(LetterPreview::id).distinct().count());
    for (LetterPreview letter : batch) picks.merge(letter.id(), 1, Integer::sum);
    if (batch.stream().anyMatch(letter -> !letter.isSeed() && seenSet.contains(letter.id()))) batchesWithSeenMember++;
   }
   // 안 본 편지가 넉넉하면 본 편지는 거의 섞이지 않는다(가중치 20에서 약 1할)
   System.out.println("본 회원 편지가 섞인 묶음: " + batchesWithSeenMember + " / " + ROUNDS);
   assertTrue(batchesWithSeenMember < ROUNDS / 4, "본 편지가 섞인 묶음 " + batchesWithSeenMember + " / " + ROUNDS);
   // 본 편지도 빠지지 않고 다시 떠내려온다
   for (String id : seen) assertTrue(picks.getOrDefault(id, 0) > 0, "이미 본 편지도 강물에 나와야 한다: " + id);
   // 안 본 회원 편지 한 통이 뽑히는 비율이 본 편지 한 통보다 확실히 높다(균등이면 1배)
   double unseenRate = members.stream().filter(id -> !seenSet.contains(id)).mapToInt(id -> picks.getOrDefault(id, 0)).average().orElseThrow();
   double seenRate = members.stream().filter(seenSet::contains).mapToInt(id -> picks.getOrDefault(id, 0)).average().orElseThrow();
   System.out.println("편지 한 통당 뽑힌 횟수: 안 본 편지 " + unseenRate + "회, 본 편지 " + seenRate + "회");
   assertTrue(unseenRate > seenRate * 8, "안 본 편지 " + unseenRate + "회, 본 편지 " + seenRate + "회");
   double unseenSeed = seeds.subList(1, 3).stream().mapToInt(id -> picks.getOrDefault(id, 0)).average().orElseThrow();
   System.out.println("시작 편지: 안 본 편지 " + unseenSeed + "회, 본 편지 " + picks.getOrDefault(seeds.get(0), 0) + "회");
   assertTrue(unseenSeed > picks.getOrDefault(seeds.get(0), 0) * 8, "시작 편지도 안 본 편지가 먼저");

   // UUID가 아니거나 이상한 값은 버리고 평소처럼 돌려준다(SQL 오류 없음)
   List<String> junk = new ArrayList<>(List.of("x'); drop table letters; --", "", "{}", "1-1-1-1-1", "null", members.get(0).toUpperCase()));
   for (int i = 0; i < 200; i++) junk.add(UUID.randomUUID().toString()); // 너무 많아도 MAX_SEEN에서 자른다
   assertEquals(5, service.river("bongnim", junk).size());
   assertEquals(15, jdbc.queryForObject("select count(*)::int from letters", Integer.class));
  } finally {
   try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) { statement.execute("drop schema if exists " + schema + " cascade"); }
  }
 }

 private static String member(JdbcTemplate jdbc, String author) {
  String id = UUID.randomUUID().toString();
  jdbc.update("insert into letters (id,bridge_id,question_id,author_user_id,display_nickname,body,theme,is_seed,status) select ?::uuid,'bongnim',q.id,?::uuid,'익명의 별','오늘도 잘 버텼어요.',1,false,'published' from questions q where q.bridge_id='bongnim'", id, author);
  return id;
 }
 private static String seed(JdbcTemplate jdbc) {
  String id = UUID.randomUUID().toString();
  jdbc.update("insert into letters (id,bridge_id,display_nickname,body,theme,is_seed,status) values (?::uuid,'bongnim','별빛안부','먼저 띄워 둔 안부',2,true,'published')", id);
  return id;
 }
}
