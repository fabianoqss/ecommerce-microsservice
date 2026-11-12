package com.example.ecommerce.order.exception;

import java.util.UUID;

public class OrderInProgressException extends RuntimeException {

    private final UUID orderId;

    public OrderInProgressException(UUID orderId) {
        super("Order " + orderId + " with this idempotency key is still being processed");
        this.orderId = orderId;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
