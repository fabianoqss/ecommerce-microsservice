package com.example.ecommerce.order.migration;

import org.flywaydb.core.Flyway;
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

/**
 * A V5 troca a PK de tb_orders de BIGINT para UUID com dados já existentes: os pedidos legados
 * precisam ganhar um UUIDv7 coerente com o order_time, e itens/outbox continuar apontando para eles.
 */
@Testcontainers
class OrderIdUuidMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

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
            // Os 48 bits mais altos do v7 são o timestamp em ms: o legado fica ordenado pelo order_time.
            long timestampMillis = orderId.getMostSignificantBits() >>> 16;
            assertThat(timestampMillis).isEqualTo(orderTime.toInstant(ZoneOffset.UTC).toEpochMilli());

            assertThat(singleUuid(sql, "SELECT order_id FROM tb_order_items")).isEqualTo(orderId);
            assertThat(singleUuid(sql, "SELECT aggregate_id FROM tb_outbox_event")).isEqualTo(orderId);
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
