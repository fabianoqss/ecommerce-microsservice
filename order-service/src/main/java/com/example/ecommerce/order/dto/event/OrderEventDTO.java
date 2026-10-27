package com.example.ecommerce.order.dto.event;

import com.example.ecommerce.order.domain.enums.ReservationMode;
import com.example.ecommerce.order.dto.client.inventory.InventoryItemDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderEventDTO(
        UUID eventId,
        Integer version,
        UUID orderId,
        String userId,
        String status,
        BigDecimal totalValue,
        LocalDateTime orderTime,
        ReservationMode reservationMode,
        List<InventoryItemDTO> items
) {
}
