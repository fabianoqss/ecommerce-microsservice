package com.example.ecommerce.notification_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderEventDTO(
        Long orderId,
        String userId,
        String status,
        BigDecimal totalValue,
        LocalDateTime orderTime
) {
}
