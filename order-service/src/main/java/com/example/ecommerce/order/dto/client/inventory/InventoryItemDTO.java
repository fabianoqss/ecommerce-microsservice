package com.example.ecommerce.order.dto.client.inventory;

public record InventoryItemDTO(
        String skuCode,
        Integer quantity
) {
}
