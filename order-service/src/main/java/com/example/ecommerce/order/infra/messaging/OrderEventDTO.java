package com.example.ecommerce.order.infra.messaging;

import com.example.ecommerce.order.domain.ReservationMode;
import com.example.ecommerce.order.dto.request.InventoryItemDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Campos novos da v3 (reservationMode e items) são aditivos e omitidos quando nulos (ORDER_CREATED v2).
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
