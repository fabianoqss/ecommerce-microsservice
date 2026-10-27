package com.example.ecommerce.inventory.dto.event;

import com.example.ecommerce.inventory.domain.enums.ReservationMode;
import com.example.ecommerce.inventory.dto.request.InventoryItemDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelledEventDTO(
        UUID eventId,
        Integer version,
        UUID orderId,
        ReservationMode reservationMode,
        List<InventoryItemDTO> items
) {
}
