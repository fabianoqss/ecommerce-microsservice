package com.example.ecommerce.product.exception;

public class ProductVersionConflictException extends RuntimeException {
    public ProductVersionConflictException(String productId) {
        super("Product " + productId + " was modified by another request; reload and try again");
    }
}
