package com.example.ecommerce.order_service.exceptions;

public class OrderAccessDeniedException extends RuntimeException {
    public OrderAccessDeniedException() {
        super("Você não tem permissão para acessar este pedido");
    }
}
