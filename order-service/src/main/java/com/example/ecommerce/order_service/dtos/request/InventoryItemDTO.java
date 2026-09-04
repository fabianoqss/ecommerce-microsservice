package com.example.ecommerce.order_service.dtos.request;

public record InventoryItemDTO(
        String skuCode,
        Integer quantity
) {
}
