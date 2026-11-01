package com.example.ecommerce.product.exception;

public class ProductVersionConflictException extends RuntimeException {
    public ProductVersionConflictException(String productId) {
        super("O produto " + productId + " foi alterado por outra requisição; recarregue e tente novamente");
    }
}
