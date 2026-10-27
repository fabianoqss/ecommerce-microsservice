package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.repository.OrderRepository;
import feign.FeignException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderReconcilerTest {

    private static final UUID ORDER_ID = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryClient inventoryClient;

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    private final MockClock clock = new MockClock();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);

    private OrderReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new OrderReconciler(orderRepository, inventoryClient, transactionTemplate, meterRegistry);
        reconciler.setMinAge(Duration.ofMinutes(2));
        reconciler.setBatchSize(50);
    }

    @Test
    void pendingGaugeReflectsCountOfPendingAndExpiredOrders() {
        when(orderRepository.countByStatusIn(List.of(OrderStatus.PENDING, OrderStatus.EXPIRED))).thenReturn(4L);

        assertThat(meterRegistry.get("orders.pending").gauge().value()).isEqualTo(4.0);
    }

    @Test
    void expiresAndCommitsBeforeCallingDeleteAndThenCancels() {
        Order pending = new Order();
        pending.setId(ORDER_ID);
        pending.setStatus(OrderStatus.PENDING);

        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(orderRepository.findReconcilableOrdersForUpdate(any(), anyInt())).thenReturn(List.of(pending));
        when(orderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            assertThat(pending.getStatus()).isEqualTo(OrderStatus.EXPIRED);
            return null;
        }).when(inventoryClient).releaseReservation(ORDER_ID);

        reconciler.reconcile();

        InOrder inOrder = inOrder(transactionManager, orderRepository, inventoryClient);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(orderRepository).findReconcilableOrdersForUpdate(any(), anyInt());
        inOrder.verify(orderRepository).saveAndFlush(pending);
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(inventoryClient).releaseReservation(ORDER_ID);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(orderRepository).saveAndFlush(pending);
        inOrder.verify(transactionManager).commit(any());

        assertThat(pending.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(pending.getCancelledBy()).isEqualTo("reconciler");
        assertThat(meterRegistry.counter("orders.reconciled", "outcome", "cancelled").count()).isEqualTo(1.0);
    }

    @Test
    void deleteFailureSchedulesBackoffAndCountsRetry() {
        Order pending = new Order();
        pending.setId(ORDER_ID);
        pending.setStatus(OrderStatus.PENDING);

        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(orderRepository.findReconcilableOrdersForUpdate(any(), anyInt())).thenReturn(List.of(pending));
        when(orderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(mock(FeignException.ServiceUnavailable.class)).when(inventoryClient).releaseReservation(ORDER_ID);

        reconciler.reconcile();

        assertThat(pending.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(pending.getReconciliationAttempts()).isEqualTo(1);
        assertThat(pending.getNextReconciliationAt()).isBetween(Instant.now().plusSeconds(4), Instant.now().plusSeconds(6));
        assertThat(meterRegistry.counter("orders.reconciled", "outcome", "retry").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("orders.reconciled", "outcome", "cancelled").count()).isZero();
    }

    @Test
    void alreadyExpiredOrderFromPreviousRoundIsRetriedAndCancelled() {
        Order expired = new Order();
        expired.setId(ORDER_ID);
        expired.setStatus(OrderStatus.EXPIRED);

        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(orderRepository.findReconcilableOrdersForUpdate(any(), anyInt())).thenReturn(List.of(expired));
        when(orderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        reconciler.reconcile();

        assertThat(expired.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(meterRegistry.counter("orders.reconciled", "outcome", "cancelled").count()).isEqualTo(1.0);
    }
    @Test
    void maxAttemptsMovesOrderToTerminalStateAndCountsFailure() {
        Order expired = new Order();
        expired.setId(ORDER_ID);
        expired.setStatus(OrderStatus.EXPIRED);
        expired.setReconciliationAttempts(2);
        reconciler.setMaxAttempts(3);
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(orderRepository.findReconcilableOrdersForUpdate(any(), anyInt())).thenReturn(List.of(expired));
        doThrow(new IllegalStateException("Inventory indisponível")).when(inventoryClient).releaseReservation(ORDER_ID);

        reconciler.reconcile();

        assertThat(expired.getStatus()).isEqualTo(OrderStatus.RECONCILIATION_FAILED);
        assertThat(expired.getReconciliationAttempts()).isEqualTo(3);
        assertThat(expired.getNextReconciliationAt()).isNull();
        assertThat(meterRegistry.counter("orders.reconciled", "outcome", "failed").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("orders.reconciled", "outcome", "retry").count()).isZero();
        verify(orderRepository).saveAndFlush(expired);
    }

    @Test
    void stopsBatchWhenTimeBudgetIsExhausted() {
        Order first = new Order();
        first.setId(ORDER_ID);
        first.setStatus(OrderStatus.EXPIRED);
        Order second = new Order();
        second.setId(UUID.randomUUID());
        second.setStatus(OrderStatus.EXPIRED);
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(orderRepository.findReconcilableOrdersForUpdate(any(), anyInt())).thenReturn(List.of(first, second));
        doAnswer(inv -> {
            clock.add(Duration.ofSeconds(20));
            return null;
        }).when(inventoryClient).releaseReservation(ORDER_ID);

        reconciler.reconcile();

        assertThat(first.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(second.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(inventoryClient, never()).releaseReservation(second.getId());
    }

    @Test
    void backoffDoublesAndIsCappedWithoutOverflow() {
        reconciler.setBaseBackoff(Duration.ofSeconds(5));
        reconciler.setMaxBackoff(Duration.ofMinutes(30));

        assertThat(reconciler.computeBackoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(reconciler.computeBackoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(reconciler.computeBackoff(10)).isEqualTo(Duration.ofMinutes(30));
        assertThat(reconciler.computeBackoff(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(30));
        reconciler.setBaseBackoff(Duration.ofMillis(Long.MAX_VALUE / 2));
        reconciler.setMaxBackoff(Duration.ofMillis(Long.MAX_VALUE));
        assertThat(reconciler.computeBackoff(5)).isEqualTo(Duration.ofMillis(Long.MAX_VALUE));
    }

    @Test
    void configuredMaximumCapsBackoffEvenBelowBase() {
        reconciler.setBaseBackoff(Duration.ofSeconds(5));
        reconciler.setMaxBackoff(Duration.ofSeconds(2));

        assertThat(reconciler.computeBackoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(reconciler.computeBackoff(5)).isEqualTo(Duration.ofSeconds(2));
    }

}
