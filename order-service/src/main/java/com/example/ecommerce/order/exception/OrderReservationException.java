package com.example.ecommerce.order.exception;

/**
 * Falha ambígua ou de concorrência com o reconciliador ao reservar/confirmar estoque na criação
 * do pedido: responde 503 Service Unavailable com ProblemDetail.
 */
public class OrderReservationException extends RuntimeException {
    public OrderReservationException(String message, Throwable cause) {
        super(message, cause);
    }

    public OrderReservationException(String message) {
        super(message);
    }
}
