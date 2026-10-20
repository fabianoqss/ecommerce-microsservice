package com.example.ecommerce.inventory.exception;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(UUID reservationId) {
        super("Reserva de estoque não encontrada: " + reservationId);
    }
}
