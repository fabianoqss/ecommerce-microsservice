package com.example.ecommerce.order.infra.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record OrderEventDTO(
        UUID eventId,
        Integer version,
        UUID orderId,
        String userId,
        String status,
        BigDecimal totalValue,
        LocalDateTime orderTime
) {
}
