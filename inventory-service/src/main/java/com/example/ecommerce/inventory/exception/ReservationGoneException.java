package com.example.ecommerce.inventory.exception;

import java.util.UUID;

public class ReservationGoneException extends RuntimeException {
    public ReservationGoneException(UUID reservationId) {
        super("Reservation has already been released or cancelled: " + reservationId);
    }
}
