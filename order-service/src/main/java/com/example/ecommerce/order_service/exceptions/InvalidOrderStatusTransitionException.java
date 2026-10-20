package com.example.ecommerce.order_service.exceptions;

import com.example.ecommerce.order_service.enums.OrderStatus;

import java.util.UUID;

public class InvalidOrderStatusTransitionException extends RuntimeException {
    public InvalidOrderStatusTransitionException(UUID id, OrderStatus from, OrderStatus to) {
        super("Pedido " + id + " não pode passar de " + from + " para " + to);
    }
}
