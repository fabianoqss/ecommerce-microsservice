package com.example.ecommerce.order_service.exceptions;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long id) {
        super("Pedido não encontrado com id: " + id);
    }
}
