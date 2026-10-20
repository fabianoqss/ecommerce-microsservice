package com.example.ecommerce.inventory.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String skuCode) {
        super("Estoque insuficiente ou SKU inexistente: " + skuCode);
    }
}
