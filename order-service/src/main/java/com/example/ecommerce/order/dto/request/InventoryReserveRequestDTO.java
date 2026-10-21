package com.example.ecommerce.order.dto.request;

import java.util.List;

public record InventoryReserveRequestDTO(
        List<InventoryItemDTO> items
) {
}
