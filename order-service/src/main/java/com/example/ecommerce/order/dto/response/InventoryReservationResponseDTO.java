package com.example.ecommerce.order.dto.response;

import com.example.ecommerce.order.dto.request.InventoryItemDTO;

import java.util.List;
import java.util.UUID;

public record InventoryReservationResponseDTO(
        UUID id,
        String status,
        List<InventoryItemDTO> items
) {
}
