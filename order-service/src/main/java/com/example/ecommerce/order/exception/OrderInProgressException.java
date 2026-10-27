package com.example.ecommerce.order.exception;

import java.util.UUID;

public class OrderInProgressException extends RuntimeException {

    private final UUID orderId;

    public OrderInProgressException(UUID orderId) {
        super("O pedido " + orderId + " com esta chave de idempotência ainda está em processamento");
        this.orderId = orderId;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
