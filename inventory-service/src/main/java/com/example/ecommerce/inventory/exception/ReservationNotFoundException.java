package com.example.ecommerce.inventory.exception;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(UUID reservationId) {
        super("Stock reservation not found: " + reservationId);
    }
}
