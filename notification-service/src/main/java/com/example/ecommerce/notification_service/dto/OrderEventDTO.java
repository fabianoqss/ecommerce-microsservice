package com.example.ecommerce.notification_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Evento ORDER_CREATED. orderId é String de propósito (tolerant reader): na v1 o order-service
 * publicava um número (Long) e na v2 publica um UUID; os dois formatos convivem na fila e no
 * outbox durante o deploy. Este serviço só usa o orderId como identificador opaco.
 */
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
