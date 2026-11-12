package com.example.ecommerce.order.exception;

import com.example.ecommerce.order.domain.enums.OrderStatus;

import java.util.UUID;

public class InvalidOrderStatusTransitionException extends RuntimeException {
    public InvalidOrderStatusTransitionException(UUID id, OrderStatus from, OrderStatus to) {
        super("Order " + id + " cannot transition from " + from + " to " + to);
    }
}
