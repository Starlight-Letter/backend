package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.starlight.greetings.application.LetterWriteService;
import com.starlight.greetings.web.ApiException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 한 회원이 서로 다른 편지를 같은 순간 여러 통 보내도 하루 한 다리 3통 제한이 지켜지는지 본다.
// 예전에는 '오늘 쓴 수 세기 → 저장' 사이에 잠금이 없어, 동시에 보낸 6통이 모두 "아직 여유 있음"으로 판단돼 저장됐다.
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest
class LetterLimitConcurrencyIntegrationTest {
 private static final String SCHEMA = "letter_limit_" + UUID.randomUUID().toString().replace('-', '_');
 private static final int CONCURRENT = 6;

 @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
  URI uri = URI.create(System.getenv("TEST_DATABASE_URL")); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + SCHEMA);
  }
  registry.add("spring.datasource.url", () -> base + "?currentSchema=" + SCHEMA);
  registry.add("spring.datasource.username", () -> auth[0]);
  registry.add("spring.datasource.password", () -> auth[1]);
  registry.add("spring.flyway.schemas", () -> SCHEMA);
  registry.add("spring.flyway.default-schema", () -> SCHEMA);
 }

 @Autowired LetterWriteService letters;
 @Autowired JdbcTemplate jdbc;

 @Test void sixSimultaneousLettersFromOneMemberSaveOnlyThree() throws Exception {
  String user = UUID.randomUUID().toString(), question = UUID.randomUUID().toString();
  jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,'동시작성','동시작성','x','x','member','active')", user);
  jdbc.update("insert into questions (id,bridge_id,body,effective_at,status) values (?::uuid,'sillim','오늘 행복했던 일은?',now()-interval '1 hour','published')", question);

  ExecutorService threads = Executors.newFixedThreadPool(CONCURRENT);
  CountDownLatch start = new CountDownLatch(1);
  try {
   List<Callable<String>> calls = IntStream.range(0, CONCURRENT).mapToObj(i -> (Callable<String>) () -> {
    Map<String, Object> input = new HashMap<>(Map.of("bridgeId", "sillim", "questionId", question, "body", "동시에 띄우는 안부 " + i,
     "theme", 1, "userId", user, "nickname", "동시작성", "idempotencyKey", "concurrent-" + i + "-key"));
    start.await();
    return letters.write(input).id();
   }).toList();
   List<Future<String>> results = calls.stream().map(threads::submit).toList();
   start.countDown(); // 여섯 요청을 같은 순간 출발시킨다
   int saved = 0, limited = 0;
   for (Future<String> result : results) {
    try { result.get(30, TimeUnit.SECONDS); saved++; }
    catch (ExecutionException error) { if (error.getCause() instanceof ApiException api && "LETTER_LIMIT".equals(api.code())) limited++; else throw error; }
   }
   assertEquals(3, saved);
   assertEquals(3, limited);
   assertEquals(3, jdbc.queryForObject("select count(*)::int from letters where author_user_id=?::uuid", Integer.class, user));
  } finally { threads.shutdownNow(); }
 }
}
