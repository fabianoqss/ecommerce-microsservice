package com.example.ecommerce.order_service.exceptions;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String productId) {
        super("Produto não encontrado: " + productId);
    }
}
