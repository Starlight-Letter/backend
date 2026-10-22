package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.starlight.greetings.application.LoginService;
import com.starlight.greetings.application.SignupService;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 동시 가입·로그인이 연결 풀 크기를 넘어도 멈추지 않는지 본다.
// 예전에는 횟수 제한(REQUIRES_NEW)을 트랜잭션 안에서 불러 요청마다 연결을 2개 잡았고, 풀 크기 이상 몰리면 모두 연결 대기 시간 뒤 실패했다.
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.connection-timeout=5000" })
class AuthConcurrencyIntegrationTest {
 private static final String SCHEMA = "auth_concurrency_" + UUID.randomUUID().toString().replace('-', '_');
 private static final int CONCURRENT = 16;

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

 @Autowired SignupService signups;
 @Autowired LoginService logins;

 @Test void signupsAndLoginsFourTimesThePoolSizeAllSucceed() throws Exception {
  ExecutorService threads = Executors.newFixedThreadPool(CONCURRENT);
  try {
   assertAllSucceed(threads, IntStream.range(0, CONCURRENT).mapToObj(i -> (Callable<Object>) () -> signups.signup("동시가입" + i, "long-password-123", "10.0.0." + i)).toList());
   assertAllSucceed(threads, IntStream.range(0, CONCURRENT).mapToObj(i -> (Callable<Object>) () -> logins.login("동시가입" + i, "long-password-123")).toList());
  } finally { threads.shutdownNow(); }
 }

 private static void assertAllSucceed(ExecutorService threads, List<Callable<Object>> calls) throws Exception {
  int succeeded = 0;
  for (Future<Object> result : threads.invokeAll(calls, 60, TimeUnit.SECONDS)) { result.get(); succeeded++; }
  assertEquals(calls.size(), succeeded);
 }
}
