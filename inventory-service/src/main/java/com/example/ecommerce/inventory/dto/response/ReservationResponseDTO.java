package com.example.ecommerce.inventory.dto.response;

import com.example.ecommerce.inventory.domain.enums.ReservationStatus;
import com.example.ecommerce.inventory.dto.request.InventoryItemDTO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "ReservationResponseDTO", description = "Reservation state in the inventory ledger")
public record ReservationResponseDTO(

        @Schema(description = "Reservation identifier (orderId)")
        UUID id,

        @Schema(description = "Current reservation state (RESERVED or RELEASED)")
        ReservationStatus status,

        @Schema(description = "Normalized reservation items, sorted by skuCode")
        List<InventoryItemDTO> items,

        @Schema(description = "Reservation or tombstone creation time")
        Instant createdAt,

        @Schema(description = "Time of the last state transition")
        Instant updatedAt
) {
}
