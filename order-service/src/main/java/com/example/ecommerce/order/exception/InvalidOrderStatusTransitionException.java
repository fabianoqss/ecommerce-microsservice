package com.example.ecommerce.order.exception;

import com.example.ecommerce.order.domain.OrderStatus;

import java.util.UUID;

public class InvalidOrderStatusTransitionException extends RuntimeException {
    public InvalidOrderStatusTransitionException(UUID id, OrderStatus from, OrderStatus to) {
        super("Pedido " + id + " não pode passar de " + from + " para " + to);
    }
}
