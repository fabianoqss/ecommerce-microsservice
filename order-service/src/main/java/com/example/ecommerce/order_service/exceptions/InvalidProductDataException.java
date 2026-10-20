package com.example.ecommerce.order_service.exceptions;

public class InvalidProductDataException extends RuntimeException {
    public InvalidProductDataException(String productId) {
        super("Dados inválidos do product-service para o produto " + productId + ": preço e nome são obrigatórios");
    }
}
