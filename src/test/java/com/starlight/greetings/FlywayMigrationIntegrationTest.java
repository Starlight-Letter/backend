package com.starlight.greetings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.net.URI;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class FlywayMigrationIntegrationTest {
    @Test
    void migratesAnEmptyDatabaseWithTheThreeBridges() throws Exception {
        String databaseUrl = System.getenv("TEST_DATABASE_URL");
        assertNotNull(databaseUrl, "TEST_DATABASE_URL must point to the disposable PostgreSQL test database");
        URI databaseUri = URI.create(databaseUrl);
        String[] credentials = databaseUri.getUserInfo().split(":", 2);
        String jdbcUrl = "jdbc:postgresql://" + databaseUri.getHost() + ":" + databaseUri.getPort() + databaseUri.getPath();
        String schema = "migration_" + UUID.randomUUID().toString().replace('-', '_');

        try (Connection connection = DriverManager.getConnection(jdbcUrl, credentials[0], credentials[1]);
             Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
            Flyway.configure()
                .dataSource(jdbcUrl, credentials[0], credentials[1])
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();

            try (ResultSet bridges = statement.executeQuery("select count(*) from " + schema + ".bridges")) {
                bridges.next();
                assertEquals(3, bridges.getInt(1));
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, credentials[0], credentials[1]);
                 Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }
}
