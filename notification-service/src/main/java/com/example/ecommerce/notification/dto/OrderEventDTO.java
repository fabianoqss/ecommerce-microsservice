package com.example.ecommerce.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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
