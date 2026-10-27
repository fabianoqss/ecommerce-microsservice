package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.Order;
import com.example.ecommerce.order.domain.OrderStatus;
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
                .description("Contagem de pedidos PENDING e EXPIRED aguardando confirmação ou reconciliação")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${ecommerce.order.reconciliation.fixed-delay:30000}")
    public void reconcile() {
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
            reconcileSingleOrder(expiredOrder);
        }
    }

    private void reconcileSingleOrder(Order expiredOrder) {
        try {
            inventoryClient.releaseReservation(expiredOrder.getId());
        } catch (Exception ex) {
            meterRegistry.counter("orders.reconciled", "outcome", "retry").increment();
            log.warn("Falha ao liberar reserva no inventory durante reconciliação (permanecerá EXPIRED): orderId={}",
                    expiredOrder.getId(), ex);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                expiredOrder.cancel("reconciler", "Reserva expirada por timeout na confirmação", LocalDateTime.now());
                orderRepository.saveAndFlush(expiredOrder);
            });
            meterRegistry.counter("orders.reconciled", "outcome", "cancelled").increment();
            log.info("Pedido expirado reconciliado e cancelado: orderId={}", expiredOrder.getId());
        } catch (OptimisticLockingFailureException ex) {
            log.debug("Pedido já cancelado por outra instância do reconciliador: orderId={}", expiredOrder.getId(), ex);
        }
    }
}
