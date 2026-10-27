package com.example.ecommerce.inventory.dto.response;

public record ReservationResult(
        ReservationResponseDTO reservation,
        boolean created
) {
}
