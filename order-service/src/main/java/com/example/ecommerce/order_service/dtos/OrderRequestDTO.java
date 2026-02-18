package com.example.ecommerce.order_service.dtos;

import java.util.List;

public record OrderRequestDTO(
        String userId,
        List<OrderItemRequestDTO> items
) {
}
