package com.example.ecommerce.order.dto.client.inventory;

import java.util.List;

public record InventoryReserveRequestDTO(
        List<InventoryItemDTO> items
) {
}
