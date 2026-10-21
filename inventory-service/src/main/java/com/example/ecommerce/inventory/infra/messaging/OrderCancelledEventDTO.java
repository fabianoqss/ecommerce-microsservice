package com.example.ecommerce.inventory.infra.messaging;

import com.example.ecommerce.inventory.domain.ReservationMode;
import com.example.ecommerce.inventory.dto.request.InventoryItemDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

// DTO próprio do inventory-service; ignora campos do evento de pedido irrelevantes ao estoque.
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelledEventDTO(
        UUID eventId,
        Integer version,
        UUID orderId,
        ReservationMode reservationMode,
        List<InventoryItemDTO> items
) {
}
