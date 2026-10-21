package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.starlight.greetings.application.JdbcBridgePassService;
import com.starlight.greetings.web.ApiException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BridgePassIntegrationTest {
 @Test void opensOnlyTheLastScannedBridgeUntilTheKeyIsRotated() throws Exception {
  String databaseUrl = System.getenv("TEST_DATABASE_URL");
  if (databaseUrl == null) return;
  URI uri = URI.create(databaseUrl); String[] auth = uri.getUserInfo().split(":", 2);
  String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
  String schema = "bridge_pass_" + UUID.randomUUID().toString().replace('-', '_');
  try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) {
   statement.execute("create schema " + schema);
   Flyway.configure().dataSource(base, auth[0], auth[1]).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
   JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(base + "?currentSchema=" + schema, auth[0], auth[1]));
   JdbcBridgePassService passes = new JdbcBridgePassService(jdbc);

   // 마이그레이션이 다리마다 서로 다른 무작위 열쇠를 만든다
   assertEquals(3, jdbc.queryForObject("select count(distinct qr_key) from bridges", Integer.class));
   String sillimKey = jdbc.queryForObject("select qr_key from bridges where id='sillim'", String.class);
   String bongnimKey = jdbc.queryForObject("select qr_key from bridges where id='bongnim'", String.class);

   // 틀린 열쇠나 다른 다리 열쇠로는 열리지 않는다
   assertEquals("QR_INVALID", assertThrows(ApiException.class, () -> passes.issue("sillim", "guess", null)).code());
   assertEquals("QR_INVALID", assertThrows(ApiException.class, () -> passes.issue("sillim", bongnimKey, null)).code());

   // 찍은 다리에만 쓸 수 있다
   String first = (String) passes.issue("sillim", sillimKey, null).get("token");
   assertEquals("sillim", passes.current(first).get("bridgeId"));
   assertDoesNotThrow(() -> passes.requireWrite(first, "sillim"));
   assertEquals("BRIDGE_LOCKED", assertThrows(ApiException.class, () -> passes.requireWrite(first, "bongnim")).code());
   assertEquals("BRIDGE_LOCKED", assertThrows(ApiException.class, () -> passes.requireWrite(null, "sillim")).code());

   // 다른 다리를 찍으면 마지막 다리 하나만 남는다
   String second = (String) passes.issue("bongnim", bongnimKey, first).get("token");
   assertNull(passes.current(first));
   assertDoesNotThrow(() -> passes.requireWrite(second, "bongnim"));
   assertEquals("BRIDGE_LOCKED", assertThrows(ApiException.class, () -> passes.requireWrite(second, "sillim")).code());

   // 만료된 허가는 쓸 수 없다
   jdbc.update("update bridge_passes set expires_at=now()-interval '1 minute'");
   assertNull(passes.current(second));
   assertEquals("BRIDGE_LOCKED", assertThrows(ApiException.class, () -> passes.requireWrite(second, "bongnim")).code());

   // 열쇠를 새로 발급하면 이전 열쇠와 그 다리 허가가 끊기고 감사 기록이 남는다
   String admin = UUID.randomUUID().toString();
   jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status,totp_secret_ciphertext) values (?::uuid,'운영자','운영자','hash','recovery','admin','active','totp')", admin);
   String third = (String) passes.issue("sillim", sillimKey, null).get("token");
   assertEquals("INVALID_REASON", assertThrows(ApiException.class, () -> passes.rotate("sillim", " ", admin)).code());
   String newKey = (String) passes.rotate("sillim", "포스터 사진 유출", admin).get("qrKey");
   assertNotEquals(sillimKey, newKey);
   assertNull(passes.current(third));
   assertEquals("QR_INVALID", assertThrows(ApiException.class, () -> passes.issue("sillim", sillimKey, null)).code());
   assertEquals("sillim", passes.issue("sillim", newKey, null).get("bridgeId"));
   assertEquals(1, jdbc.queryForObject("select count(*) from admin_actions where action='rotate_qr_key' and target_id='sillim'", Integer.class));
   assertEquals(newKey, passes.qrKeys().stream().filter(item -> "sillim".equals(item.get("bridgeId"))).findFirst().orElseThrow().get("qrKey"));
  } finally {
   try (Connection connection = DriverManager.getConnection(base, auth[0], auth[1]); Statement statement = connection.createStatement()) { statement.execute("drop schema if exists " + schema + " cascade"); }
  }
 }
}
