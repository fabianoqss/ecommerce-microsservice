package com.example.ecommerce.order_service.dtos;

public record InventoryResponseDTO(
        String skuCode,
        Integer quantity,
        boolean inStock
) {
}
