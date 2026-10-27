package com.example.ecommerce.order.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OrderIdUuidMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @BeforeEach
    void resetDatabase() throws Exception {
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        }
    }

    @Test
    void v5PreservesLegacyOrdersAndTheirReferences() throws Exception {
        migrateTo("4");

        LocalDateTime orderTime = LocalDateTime.of(2026, 3, 26, 14, 30);
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO tb_orders (id, order_time, status, userid, total_value) "
                    + "VALUES (7, '" + orderTime + "', 'CONFIRMED', 'owner-sub', 20.00)");
            sql.execute("INSERT INTO tb_order_items (product_id, product_name, quantity, price, order_id) "
                    + "VALUES ('p1', 'Produto', 2, 10.00, 7)");
            sql.execute("INSERT INTO tb_outbox_event (event_id, aggregate_id, event_type, payload, status, attempts, created_at) "
                    + "VALUES ('" + UUID.randomUUID() + "', 7, 'ORDER_CREATED', '{}', 'SENT', 1, now())");
        }

        migrateTo("5");

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            ResultSet order = sql.executeQuery("SELECT id, userid FROM tb_orders");
            assertThat(order.next()).isTrue();
            UUID orderId = order.getObject("id", UUID.class);
            assertThat(order.getString("userid")).isEqualTo("owner-sub");

            assertThat(orderId.version()).isEqualTo(7);
            assertThat(orderId.variant()).isEqualTo(2);
            long timestampMillis = orderId.getMostSignificantBits() >>> 16;
            assertThat(timestampMillis).isEqualTo(orderTime.toInstant(ZoneOffset.UTC).toEpochMilli());

            assertThat(singleUuid(sql, "SELECT order_id FROM tb_order_items")).isEqualTo(orderId);
            assertThat(singleUuid(sql, "SELECT aggregate_id FROM tb_outbox_event")).isEqualTo(orderId);
        }
    }

    @Test
    void v7MarksExistingOrdersAsLegacyAndBackfillsConfirmedAtForConfirmedAndCancelled() throws Exception {
        migrateTo("6");

        UUID confirmedId = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a01");
        UUID cancelledId = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a02");
        UUID pendingId = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a03");
        LocalDateTime confirmedTime = LocalDateTime.of(2026, 5, 10, 12, 0);
        LocalDateTime cancelledTime = LocalDateTime.of(2026, 5, 11, 15, 45);
        LocalDateTime pendingTime = LocalDateTime.of(2026, 5, 12, 9, 15);

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO tb_orders (id, order_time, status, userid, total_value) VALUES "
                    + "('" + confirmedId + "', '" + confirmedTime + "', 'CONFIRMED', 'u1', 50.00), "
                    + "('" + cancelledId + "', '" + cancelledTime + "', 'CANCELLED', 'u1', 30.00), "
                    + "('" + pendingId + "', '" + pendingTime + "', 'PENDING', 'u1', 10.00)");
            sql.execute("INSERT INTO tb_outbox_event (event_id, aggregate_id, event_type, payload, status, attempts, created_at) "
                    + "VALUES ('" + UUID.randomUUID() + "', '" + confirmedId + "', 'ORDER_CREATED', '{}', 'PENDING', 0, now())");
        }

        migrateTo("7");

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            try (ResultSet rs = sql.executeQuery(
                    "SELECT id, status, reservation_mode, confirmed_at, (confirmed_at = order_time) AS matches_order_time "
                            + "FROM tb_orders ORDER BY order_time ASC")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("id", UUID.class)).isEqualTo(confirmedId);
                assertThat(rs.getString("reservation_mode")).isEqualTo("LEGACY");
                assertThat(rs.getTimestamp("confirmed_at")).isNotNull();
                assertThat(rs.getBoolean("matches_order_time")).isTrue();

                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("id", UUID.class)).isEqualTo(cancelledId);
                assertThat(rs.getString("reservation_mode")).isEqualTo("LEGACY");
                assertThat(rs.getTimestamp("confirmed_at")).isNotNull();
                assertThat(rs.getBoolean("matches_order_time")).isTrue();

                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("id", UUID.class)).isEqualTo(pendingId);
                assertThat(rs.getString("reservation_mode")).isEqualTo("LEGACY");
                assertThat(rs.getTimestamp("confirmed_at")).isNull();
            }

            sql.execute("UPDATE tb_orders SET status = 'EXPIRED' WHERE id = '" + pendingId + "'");

            try (ResultSet outbox = sql.executeQuery("SELECT next_attempt_at FROM tb_outbox_event")) {
                assertThat(outbox.next()).isTrue();
                assertThat(outbox.getTimestamp("next_attempt_at")).isNotNull();
            }
        }
    }

    private static UUID singleUuid(Statement sql, String query) throws Exception {
        ResultSet result = sql.executeQuery(query);
        assertThat(result.next()).isTrue();
        return result.getObject(1, UUID.class);
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
