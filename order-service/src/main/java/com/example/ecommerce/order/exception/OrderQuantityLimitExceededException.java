package com.example.ecommerce.order.exception;

public class OrderQuantityLimitExceededException extends RuntimeException {
    public OrderQuantityLimitExceededException(String productId, int limit) {
        super("A quantidade total do produto " + productId + " excede o limite de " + limit + " por pedido");
    }
}
