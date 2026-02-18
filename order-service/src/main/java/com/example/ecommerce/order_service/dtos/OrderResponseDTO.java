package com.example.ecommerce.order_service.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponseDTO (
        Long id,
        String status,
        BigDecimal totalValue,
        LocalDateTime orderTime,
        List<OrderItemResponseDTO> items
) {
}
