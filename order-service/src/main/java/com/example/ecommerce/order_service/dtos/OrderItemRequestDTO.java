package com.example.ecommerce.order_service.dtos;

public record OrderItemRequestDTO(
        String productId,
        Integer quantity
) {
}
