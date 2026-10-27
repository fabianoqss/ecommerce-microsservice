package com.example.ecommerce.order.domain.entity;

import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.exception.InvalidOrderStatusTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCancelTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 15, 0);
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-20T18:00:00Z");

    @Test
    void pendingOrderIsConfirmedWithTimestamp() {
        Order order = order(OrderStatus.PENDING);

        order.confirm(CONFIRMED_AT);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "EXPIRED", "CANCELLED", "COMPLETED"})
    void nonPendingOrderCannotBeConfirmed(OrderStatus currentStatus) {
        Order order = order(currentStatus);

        assertThatThrownBy(() -> order.confirm(CONFIRMED_AT))
                .isInstanceOf(InvalidOrderStatusTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(currentStatus);
        assertThat(order.getConfirmedAt()).isNull();
    }

    @Test
    void pendingOrderExpires() {
        Order order = order(OrderStatus.PENDING);

        order.expire();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.getConfirmedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "EXPIRED", "CANCELLED", "COMPLETED"})
    void nonPendingOrderCannotExpire(OrderStatus currentStatus) {
        Order order = order(currentStatus);

        assertThatThrownBy(order::expire)
                .isInstanceOf(InvalidOrderStatusTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(currentStatus);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "EXPIRED", "CONFIRMED"})
    void pendingExpiredOrConfirmedOrderIsCancelledWithAudit(OrderStatus initialStatus) {
        Order order = order(initialStatus);

        assertThat(order.cancel("actor-sub", "Comprei errado", NOW)).isTrue();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo("actor-sub");
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        assertThat(order.getCancellationReason()).isEqualTo("Comprei errado");
    }

    @Test
    void cancellingAnAlreadyCancelledOrderIsANoOp() {
        Order order = order(OrderStatus.CONFIRMED);
        order.cancel("first-sub", "primeiro", NOW);

        assertThat(order.cancel("second-sub", "segundo", NOW.plusMinutes(1))).isFalse();

        assertThat(order.getCancelledBy()).isEqualTo("first-sub");
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
    }

    @Test
    void completedOrderCannotBeCancelled() {
        Order order = order(OrderStatus.COMPLETED);

        assertThatThrownBy(() -> order.cancel("actor-sub", null, NOW))
                .isInstanceOf(InvalidOrderStatusTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    private static Order order(OrderStatus status) {
        Order order = new Order();
        order.setStatus(status);
        return order;
    }
}
