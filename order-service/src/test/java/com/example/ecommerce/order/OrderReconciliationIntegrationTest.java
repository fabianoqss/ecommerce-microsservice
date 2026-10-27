package com.example.ecommerce.order;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.service.OrderReconciler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class OrderReconciliationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired
    private OrderRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private InventoryClient inventoryClient;
    private OrderReconciler reconciler;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        inventoryClient = mock(InventoryClient.class);
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new OrderReconciler(repository, inventoryClient, new TransactionTemplate(transactionManager), meterRegistry);
        reconciler.setBatchSize(3);
        reconciler.setBaseBackoff(Duration.ofHours(1));
        reconciler.setMaxBackoff(Duration.ofHours(1));
        doAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException("Inventory indisponível");
        }).when(inventoryClient).releaseReservation(any());
    }

    @Test
    void failingExpiredOrdersDoNotStarveNewerPendingOrder() {
        for (int i = 0; i < 7; i++) {
            save(OrderStatus.EXPIRED, LocalDateTime.now().minusHours(2).plusSeconds(i));
        }
        UUID pendingId = save(OrderStatus.PENDING, LocalDateTime.now().minusMinutes(10)).getId();

        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        Order processed = repository.findById(pendingId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(processed.getReconciliationAttempts()).isEqualTo(1);
        assertThat(processed.getNextReconciliationAt()).isAfter(Instant.now());
        verify(inventoryClient).releaseReservation(pendingId);
        assertThat(repository.findAll()).allSatisfy(order -> assertThat(order.getReconciliationAttempts()).isEqualTo(1));
        assertThat(meterRegistry.get("orders.pending").gauge().value()).isEqualTo(8);
        assertThat(meterRegistry.get("orders.reconciliation.oldest.age.seconds").gauge().value()).isZero();
    }

    @Test
    void exhaustedRetriesPersistTerminalStateAndLeavePendingGauge() {
        reconciler.setMaxAttempts(1);
        UUID id = save(OrderStatus.EXPIRED, LocalDateTime.now().minusMinutes(10)).getId();

        reconciler.reconcile();
        reconciler.reconcile();

        Order failed = repository.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.RECONCILIATION_FAILED);
        assertThat(failed.getReconciliationAttempts()).isEqualTo(1);
        assertThat(failed.getVersion()).isEqualTo(1);
        assertThat(repository.findByUserIDAndConfirmedAtIsNotNull("owner-sub", Pageable.unpaged())).isEmpty();
        assertThat(repository.findByIdAndUserID(id, "owner-sub")).isPresent();
        verify(inventoryClient).releaseReservation(id);
        assertThat(meterRegistry.get("orders.reconciled").tag("outcome", "failed").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("orders.pending").gauge().value()).isZero();
    }

    @Test
    void queryOrdersByDueTimeBeforeOrderTimeAndSkipsFutureAttempts() {
        Order older = save(OrderStatus.EXPIRED, LocalDateTime.now().minusHours(3));
        Order earlierDue = save(OrderStatus.EXPIRED, LocalDateTime.now().minusHours(2));
        Order future = save(OrderStatus.EXPIRED, LocalDateTime.now().minusHours(4));
        jdbcTemplate.update("UPDATE tb_orders SET next_reconciliation_at = ? WHERE id = ?",
                Instant.now().minusSeconds(30).atOffset(ZoneOffset.UTC), older.getId());
        jdbcTemplate.update("UPDATE tb_orders SET next_reconciliation_at = ? WHERE id = ?",
                Instant.now().minusSeconds(60).atOffset(ZoneOffset.UTC), earlierDue.getId());
        jdbcTemplate.update("UPDATE tb_orders SET next_reconciliation_at = ? WHERE id = ?",
                Instant.now().plusSeconds(60).atOffset(ZoneOffset.UTC), future.getId());

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(repository.findReconcilableOrdersForUpdate(LocalDateTime.now().minusMinutes(2), 3))
                        .extracting(Order::getId).containsExactly(earlierDue.getId(), older.getId()));
        assertThat(meterRegistry.get("orders.reconciliation.oldest.age.seconds").gauge().value())
                .isBetween(10799.0, 10805.0);
    }

    private Order save(OrderStatus status, LocalDateTime orderTime) {
        Order order = new Order();
        order.setStatus(status);
        order.setOrderTime(orderTime);
        order.setUserID("owner-sub");
        return repository.saveAndFlush(order);
    }
}
