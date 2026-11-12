package com.example.ecommerce.order.exception;

public class InvalidProductDataException extends RuntimeException {
    public InvalidProductDataException(String productId) {
        super("Invalid product-service data for product " + productId + ": price and name are required");
    }
}
