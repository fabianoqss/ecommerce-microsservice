package com.example.ecommerce.product.exception;

public class PreconditionRequiredException extends RuntimeException {
    public PreconditionRequiredException(String message) {
        super(message);
    }
}
