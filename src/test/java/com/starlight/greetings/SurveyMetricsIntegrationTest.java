package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.starlight.greetings.application.JdbcSurveyMetricsService;
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

// 지표 요약은 방문 기록을 한 번만 읽어 종류·다리별로 나눈다. 나눈 결과가 화면이 쓰는 형태(다리 순서, 다리 없는 기록은 마지막)와 기간 조건을 지키는지 본다.
class SurveyMetricsIntegrationTest {
 @Test void summarizesEventsByTypeBridgeViewerAndPeriod() throws Exception {
  String databaseUrl = System.getenv("TEST_DATABASE_URL");
  if (databaseUrl == null) return;
  URI uri = URI.create(databaseUrl); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  String schema = "metrics_" + UUID.randomUUID().toString().replace('-', '_');
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + schema);
   Flyway.configure().dataSource(base, auth[0], auth[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
   DriverManagerDataSource dataSource = new DriverManagerDataSource(base + "?currentSchema=" + schema, auth[0], auth[1]);
   JdbcTemplate jdbc = new JdbcTemplate(dataSource); JdbcSurveyMetricsService service = new JdbcSurveyMetricsService(jdbc);
   event(jdbc, "home_visit", null, "guest", "2026-10-10T03:00:00Z");
   event(jdbc, "home_visit", null, "guest", "2026-10-10T03:00:00Z");
   event(jdbc, "home_visit", null, "member", "2026-10-10T03:00:00Z");
   event(jdbc, "home_visit", null, null, "2026-10-10T03:00:00Z");
   event(jdbc, "bridge_visit", "sillim", "member", "2026-10-10T03:00:00Z");
   event(jdbc, "bridge_visit", "bongnim", "guest", "2026-10-10T03:00:00Z");
   event(jdbc, "qr_entry", "sillim2", null, "2026-10-10T03:00:00Z");
   event(jdbc, "survey_open", null, null, "2026-10-10T03:00:00Z");
   event(jdbc, "survey_open", "bongnim", null, "2026-10-10T03:00:00Z");
   event(jdbc, "bridge_visit", "bongnim", "guest", "2026-09-01T03:00:00Z"); // 기간 밖

   Map<String, Object> all = service.summary(null, null);
   assertEquals(Map.of("total", 4, "guest", 2, "member", 1), all.get("homeVisits"));
   assertEquals(List.of(Map.of("bridgeId", "bongnim", "total", 2, "guest", 2, "member", 0), Map.of("bridgeId", "sillim", "total", 1, "guest", 0, "member", 1)), all.get("bridgeVisits"));
   assertEquals(List.of(Map.of("bridgeId", "sillim2", "count", 1)), all.get("qrEntries"));
   assertEquals(List.of(), all.get("letterOpens"));
   assertEquals(List.of(), all.get("fiveReached"));
   List<?> surveyOpens = (List<?>) all.get("surveyOpens");
   assertEquals(Map.of("bridgeId", "bongnim", "count", 1), surveyOpens.get(0));
   assertEquals(null, ((Map<?, ?>) surveyOpens.get(1)).get("bridgeId"));

   Map<String, Object> october = service.summary("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z");
   assertEquals(List.of(Map.of("bridgeId", "bongnim", "total", 1, "guest", 1, "member", 0), Map.of("bridgeId", "sillim", "total", 1, "guest", 0, "member", 1)), october.get("bridgeVisits"));
   assertEquals(Map.of("total", 0, "guest", 0, "member", 0), service.summary("2026-10-11T00:00:00Z", null).get("homeVisits"));

   // 별자리: 업적별 인원, 별 개수 분포(별 0개 회원 포함, 완성 표시는 별 개수에서 뺀다), 특별한 병 편지 수
   String lit = UUID.randomUUID().toString(), dark = UUID.randomUUID().toString();
   jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,'별','별','h','r','member','active'),(?::uuid,'밤','밤','h','r','member','active')", lit, dark);
   jdbc.update("insert into user_achievements (user_id,code) values (?::uuid,'first_letter'),(?::uuid,'two_nights'),(?::uuid,'constellation')", lit, lit, lit);
   jdbc.update("insert into letters (id,bridge_id,display_nickname,body,theme,is_seed,status) values (gen_random_uuid(),'sillim','별빛안부','반짝',5,true,'published'),(gen_random_uuid(),'sillim','별빛안부','반짝',1,true,'published')");
   @SuppressWarnings("unchecked") Map<String, Object> stars = (Map<String, Object>) service.summary(null, null).get("achievements");
   assertEquals(List.of(Map.of("code", "constellation", "count", 1), Map.of("code", "first_letter", "count", 1), Map.of("code", "two_nights", "count", 1)), stars.get("byCode"));
   assertEquals(List.of(Map.of("stars", 0, "members", 1), Map.of("stars", 2, "members", 1)), stars.get("starDistribution"));
   assertEquals(List.of(Map.of("theme", 5, "count", 1)), stars.get("specialBottles"));
  } finally {
   try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) { statement.execute("drop schema if exists " + schema + " cascade"); }
  }
 }

 private static void event(JdbcTemplate jdbc, String type, String bridge, String viewer, String at) {
  jdbc.update("insert into metric_events (id,event_type,bridge_id,viewer_kind,created_at) values (?::uuid,?,?,?,?::timestamptz)", UUID.randomUUID().toString(), type, bridge, viewer, at);
 }
}
