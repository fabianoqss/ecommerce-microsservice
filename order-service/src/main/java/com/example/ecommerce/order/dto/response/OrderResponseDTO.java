package com.example.ecommerce.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(name = "OrderResponseDTO", description = "Order response data")
public record OrderResponseDTO (
        @Schema(description = "Database-generated order ID", example = "01929b2e-7c3a-7d4f-9b1e-2f6a8c4d5e10", accessMode = Schema.AccessMode.READ_ONLY)
        UUID id,
        @Schema(description = "Order status", example = "PLACED")
        String status,
        @Schema(description = "Order total", example = "699.80")
        BigDecimal totalValue,
        @Schema(description = "Order creation date and time", example = "2026-03-26T14:30:00")
        LocalDateTime orderTime,
        @Schema(description = "Order items")
        List<OrderItemResponseDTO> items
) {
}
