package com.example.ecommerce.inventory.dto.response;

// Resultado interno da operação PUT de reserva para distinguir 201 (created) de 200 (replay).
public record ReservationResult(
        ReservationResponseDTO reservation,
        boolean created
) {
}
