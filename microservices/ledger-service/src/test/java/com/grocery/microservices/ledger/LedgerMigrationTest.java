package com.grocery.microservices.ledger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerMigrationTest {
    @Test
    void upgradePreservesEntriesAndOrderUniqueness() throws Exception {
        String url = "jdbc:h2:mem:ledger-migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration/ledger").target("1").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO summary (user_id, order_id, total_amount) "
                    + "VALUES ('customer-1', 42, 19.50)");

            Flyway.configure().dataSource(url, "sa", "")
                    .locations("classpath:db/migration/ledger").load().migrate();

            try (var rows = statement.executeQuery("SELECT user_id, order_id, total_amount, currency FROM ledger")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("user_id")).isEqualTo("customer-1");
                assertThat(rows.getLong("order_id")).isEqualTo(42);
                assertThat(rows.getBigDecimal("total_amount")).isEqualByComparingTo("19.50");
                assertThat(rows.getString("currency")).isEqualTo("SEK");
                assertThat(rows.next()).isFalse();
            }
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO ledger (user_id, order_id) VALUES ('customer-1', 42)"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23505"));
        }
    }
}
