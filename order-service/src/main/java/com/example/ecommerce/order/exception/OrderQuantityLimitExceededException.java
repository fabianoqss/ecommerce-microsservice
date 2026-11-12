package com.example.ecommerce.order.exception;

public class OrderQuantityLimitExceededException extends RuntimeException {
    public OrderQuantityLimitExceededException(String productId, int limit) {
        super("Total quantity for product " + productId + " exceeds the limit of " + limit + " per order");
    }
}
