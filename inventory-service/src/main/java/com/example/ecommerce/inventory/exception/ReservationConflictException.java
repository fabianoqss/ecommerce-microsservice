package com.example.ecommerce.inventory.exception;

import java.util.UUID;

public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(UUID reservationId) {
        super("Reserva já existe com itens divergentes: " + reservationId);
    }
}
