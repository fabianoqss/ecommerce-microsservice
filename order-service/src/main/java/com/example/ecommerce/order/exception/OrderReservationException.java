package com.example.ecommerce.order.exception;

public class OrderReservationException extends RuntimeException {
    public OrderReservationException(String message, Throwable cause) {
        super(message, cause);
    }

    public OrderReservationException(String message) {
        super(message);
    }
}
