package com.example.ecommerce.order.dto.request;

public record InventoryItemDTO(
        String skuCode,
        Integer quantity
) {
}
