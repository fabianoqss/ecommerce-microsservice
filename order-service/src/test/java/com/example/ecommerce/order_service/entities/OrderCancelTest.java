package com.example.ecommerce.order_service.entities;

import com.example.ecommerce.order_service.enums.OrderStatus;
import com.example.ecommerce.order_service.exceptions.InvalidOrderStatusTransitionException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCancelTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 15, 0);

    @Test
    void confirmedOrderIsCancelledWithAudit() {
        Order order = order(OrderStatus.CONFIRMED);

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
