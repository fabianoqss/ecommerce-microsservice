package com.example.ecommerce.inventory.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String skuCode) {
        super("Insufficient stock or nonexistent SKU: " + skuCode);
    }
}
