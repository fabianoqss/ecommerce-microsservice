package com.example.ecommerce.order.dto.client.inventory;

import java.util.List;
import java.util.UUID;

public record InventoryReservationResponseDTO(
        UUID id,
        String status,
        List<InventoryItemDTO> items
) {
}
