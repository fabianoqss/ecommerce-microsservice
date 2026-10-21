package com.example.ecommerce.order.exception;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID id) {
        super("Pedido não encontrado com id: " + id);
    }
}
