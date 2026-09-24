package db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

// Explicit opt-in: checks temporary tables, then applies V18 to the local database.
@EnabledIfEnvironmentVariable(named = "EMAIL_SCHEMA_LOCAL_CHECK", matches = "true")
class EmailVerificationMigrationLocalTest {
    @Test
    void preserveStatusesApplyFlywayAndInsert() throws Exception {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/.env.local"))) {
            props.load(reader);
        }
        String url = props.getProperty("DB_URL");
        assertThat(url).matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):.*");
        String user = props.getProperty("DB_USERNAME");
        String password = props.getProperty("DB_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            var migration = new V18__unify_email_verification_verified();
            for (String columns : new String[]{"verified BIT NOT NULL", "is_verified BIT NOT NULL",
                    "is_verified BIT NOT NULL, verified BIT NOT NULL"}) {
                statement.execute("CREATE TEMPORARY TABLE email_verifications (id INT PRIMARY KEY, " + columns + ")");
                boolean both = columns.contains(",");
                statement.execute("INSERT INTO email_verifications VALUES (1, 0" + (both ? ", 0" : "") + "), (2, 1" + (both ? ", 1" : "") + ")");
                migration.migrate(context);
                try (var result = statement.executeQuery("SELECT verified FROM email_verifications ORDER BY id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                    assertThat(result.next()).isFalse();
                }
                assertSingleStatusColumn(statement);
                statement.execute("DROP TEMPORARY TABLE email_verifications");
            }
            statement.execute("CREATE TEMPORARY TABLE email_verifications (is_verified BIT NOT NULL, verified BIT NOT NULL)");
            statement.execute("INSERT INTO email_verifications VALUES (1, 0), (0, 1)");
            assertThatThrownBy(() -> migration.migrate(context)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("Conflicting");
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM email_verifications WHERE is_verified <> verified")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(2);
            }
            statement.execute("DROP TEMPORARY TABLE email_verifications");
        }
        var flyway = Flyway.configure().dataSource(url, user, password).load();
        for (var pending : flyway.info().pending()) {
            assertThat(pending.getVersion().getVersion()).isEqualTo("18");
        }
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("18");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            assertSingleStatusColumn(statement);
            connection.setAutoCommit(false);
            try {
                assertThat(statement.executeUpdate("INSERT INTO email_verifications "
                        + "(attempt_count,email,expiration_time,last_sent_at,send_count,verification_code,verified) "
                        + "VALUES (0, 'migration-check-" + java.util.UUID.randomUUID() + "@example.invalid', "
                        + "DATE_ADD(NOW(), INTERVAL 5 MINUTE), NOW(), 1, '123456', false)")).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertSingleStatusColumn(Statement statement) throws SQLException {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (var columns = statement.executeQuery("SHOW COLUMNS FROM email_verifications")) {
            while (columns.next()) names.add(columns.getString("Field"));
        }
        assertThat(names).contains("verified").doesNotContain("is_verified");
    }
}
