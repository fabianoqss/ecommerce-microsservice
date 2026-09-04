package com.example.ecommerce.order_service.dtos.response;

public record InventoryResponseDTO(
        String skuCode,
        Integer quantity,
        boolean inStock
) {
}
