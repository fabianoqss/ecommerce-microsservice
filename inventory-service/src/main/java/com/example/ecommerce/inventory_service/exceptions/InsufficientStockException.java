package com.example.ecommerce.inventory_service.exceptions;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String skuCode) {
        super("Estoque insuficiente ou SKU inexistente: " + skuCode);
    }
}
