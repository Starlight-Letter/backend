package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starlight.greetings.application.JdbcEngagementService;
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
}
