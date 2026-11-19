package org.example.achat.inventory.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class InventorySchemaMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void createsAnIdempotencyKeyTableWithAUniqueConstraint() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stocks (stock_id, label, quantity, minimum_quantity)
                    VALUES (7, 'Main warehouse', 20, 5)
                    """);
            statement.executeUpdate("""
                    INSERT INTO assignment_idempotency_keys
                        (idempotency_key, product_id, stock_id, created_at)
                    VALUES ('key-1', 11, 7, NOW(6))
                    """);

            try (ResultSet keys = statement.executeQuery(
                    "SELECT product_id, stock_id FROM assignment_idempotency_keys WHERE idempotency_key = 'key-1'")) {
                keys.next();
                assertThat(keys.getLong("product_id")).isEqualTo(11L);
                assertThat(keys.getLong("stock_id")).isEqualTo(7L);
            }

            boolean duplicateRejected = false;
            try {
                statement.executeUpdate("""
                        INSERT INTO assignment_idempotency_keys
                            (idempotency_key, product_id, stock_id, created_at)
                        VALUES ('key-1', 12, 8, NOW(6))
                        """);
            } catch (SQLIntegrityConstraintViolationException expected) {
                duplicateRejected = true;
            }
            assertThat(duplicateRejected).isTrue();
        }
    }
}
