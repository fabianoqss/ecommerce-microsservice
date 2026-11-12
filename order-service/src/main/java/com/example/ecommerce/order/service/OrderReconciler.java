package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.repository.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class OrderReconciler {

    private static final List<OrderStatus> PENDING_AND_EXPIRED = List.of(OrderStatus.PENDING, OrderStatus.EXPIRED);

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    @Setter
    @Value("${ecommerce.order.reconciliation.min-age:2m}")
    private Duration minAge = Duration.ofMinutes(2);

    @Setter
    @Value("${ecommerce.order.reconciliation.batch-size:50}")
    private int batchSize = 50;

    @Setter
    @Value("${ecommerce.order.reconciliation.max-run-duration:20s}")
    private Duration maxRunDuration = Duration.ofSeconds(20);

    @Setter
    @Value("${ecommerce.order.reconciliation.base-backoff:5s}")
    private Duration baseBackoff = Duration.ofSeconds(5);

    @Setter
    @Value("${ecommerce.order.reconciliation.max-backoff:30m}")
    private Duration maxBackoff = Duration.ofMinutes(30);

    @Setter
    @Value("${ecommerce.order.reconciliation.max-attempts:100}")
    private int maxAttempts = 100;

    public OrderReconciler(
            OrderRepository orderRepository,
            InventoryClient inventoryClient,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;

        Gauge.builder("orders.pending", orderRepository, repo -> repo.countByStatusIn(PENDING_AND_EXPIRED))
                .description("Count of PENDING and EXPIRED orders awaiting confirmation or reconciliation")
                .register(meterRegistry);
        Gauge.builder("orders.reconciliation.oldest.age.seconds", orderRepository,
                        repo -> repo.findOldestExpiredOrderDueForReconciliation()
                                .map(time -> (double) Math.max(0, Duration.between(time, LocalDateTime.now()).toSeconds()))
                                .orElse(0.0))
                .description("Age in seconds of the oldest EXPIRED order eligible for reconciliation")
                .register(meterRegistry);
    }

    @Scheduled(scheduler = "orderReconcilerScheduler", fixedDelayString = "${ecommerce.order.reconciliation.fixed-delay:30000}")
    public void reconcile() {
        long startedAt = meterRegistry.config().clock().monotonicTime();
        LocalDateTime cutoff = LocalDateTime.now().minus(minAge);

        List<Order> expiredOrders = transactionTemplate.execute(status -> {
            List<Order> candidates = orderRepository.findReconcilableOrdersForUpdate(cutoff, batchSize);
            return candidates.stream().map(order -> {
                if (order.getStatus() == OrderStatus.PENDING) {
                    order.expire();
                    return orderRepository.saveAndFlush(order);
                }
                return order;
            }).toList();
        });

        if (expiredOrders == null || expiredOrders.isEmpty()) {
            return;
        }

        for (Order expiredOrder : expiredOrders) {
            if (meterRegistry.config().clock().monotonicTime() - startedAt >= maxRunDuration.toNanos()) {
                break;
            }
            reconcileSingleOrder(expiredOrder);
        }
    }

    Duration computeBackoff(int attempts) {
        long baseMillis = Math.max(1L, baseBackoff.toMillis());
        long maxMillis = Math.max(1L, maxBackoff.toMillis());
        int shift = Math.min(Math.max(0, attempts - 1), 30);
        long delayMillis;
        try {
            delayMillis = Math.multiplyExact(baseMillis, 1L << shift);
        } catch (ArithmeticException ex) {
            delayMillis = maxMillis;
        }
        return Duration.ofMillis(Math.min(delayMillis, maxMillis));
    }

    private void recordFailure(Order order, Exception failure) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Instant nextAttemptAt = Instant.now().plus(computeBackoff(order.getReconciliationAttempts() + 1));
                order.recordReconciliationFailure(nextAttemptAt, maxAttempts);
                orderRepository.saveAndFlush(order);
            });
            if (order.getStatus() == OrderStatus.RECONCILIATION_FAILED) {
                meterRegistry.counter("orders.reconciled", "outcome", "failed").increment();
                log.error("Reconciliation stopped after {} attempts: orderId={}",
                        order.getReconciliationAttempts(), order.getId(), failure);
            } else {
                meterRegistry.counter("orders.reconciled", "outcome", "retry").increment();
                log.warn("Failed to release reservation, attempt {}/{}, next attempt at {}: orderId={}",
                        order.getReconciliationAttempts(), maxAttempts, order.getNextReconciliationAt(), order.getId(), failure);
            }
        } catch (OptimisticLockingFailureException ex) {
            log.debug("Order already modified during reconciliation: orderId={}", order.getId(), ex);
        }
    }

    private void reconcileSingleOrder(Order expiredOrder) {
        try {
            inventoryClient.releaseReservation(expiredOrder.getId());
        } catch (Exception ex) {
            recordFailure(expiredOrder, ex);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                expiredOrder.cancel("reconciler", "Reservation expired due to confirmation timeout", LocalDateTime.now());
                orderRepository.saveAndFlush(expiredOrder);
            });
            meterRegistry.counter("orders.reconciled", "outcome", "cancelled").increment();
            log.info("Expired order reconciled and cancelled: orderId={}", expiredOrder.getId());
        } catch (OptimisticLockingFailureException ex) {
            log.debug("Order already cancelled by another reconciler instance: orderId={}", expiredOrder.getId(), ex);
        }
    }
}
