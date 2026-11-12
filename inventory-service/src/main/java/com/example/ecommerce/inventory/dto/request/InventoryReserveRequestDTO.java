package com.example.ecommerce.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "InventoryReserveRequestDTO", description = "Items to reserve or release in inventory")
public record InventoryReserveRequestDTO(

        @Schema(description = "Order items (between 1 and 50 items)")
        @NotEmpty(message = "Item list must not be empty")
        @Size(min = 1, max = 50, message = "Item list must contain between 1 and 50 items")
        @Valid
        List<@NotNull(message = "List item must not be null") InventoryItemDTO> items
) {
}
