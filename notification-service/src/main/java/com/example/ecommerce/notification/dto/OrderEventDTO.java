package com.example.ecommerce.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Evento de pedido (ORDER_CREATED / ORDER_CANCELLED). orderId é String de propósito (tolerant
 * reader): na v1 o order-service publicava um número (Long) e a partir da v2 publica um UUID; na
 * v3 o ORDER_CANCELLED inclui campos extras de estoque (reservationMode, items) ignorados aqui.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEventDTO(
        UUID eventId,
        Integer version,
        String orderId,
        String userId,
        String status,
        BigDecimal totalValue,
        LocalDateTime orderTime
) {
}
