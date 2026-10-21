package com.example.ecommerce.inventory.exception;

import java.util.UUID;

public class ReservationGoneException extends RuntimeException {
    public ReservationGoneException(UUID reservationId) {
        super("Reserva já foi liberada ou cancelada: " + reservationId);
    }
}
