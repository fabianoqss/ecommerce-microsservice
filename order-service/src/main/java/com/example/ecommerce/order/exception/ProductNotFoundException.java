package com.example.ecommerce.order.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String productId) {
        super("Produto não encontrado: " + productId);
    }
}
