package com.example.ecommerce.order_service.dtos.request;

import java.util.List;

public record InventoryReserveRequestDTO(
        List<InventoryItemDTO> items
) {
}
