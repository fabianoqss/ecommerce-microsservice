package com.example.ecommerce.inventory.exception;

import java.util.UUID;

public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(UUID reservationId) {
        super("Reservation already exists with different items: " + reservationId);
    }
}
