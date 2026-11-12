package com.example.ecommerce.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "InventoryUpdateDTO", description = "Data to update the stock quantity")
public record InventoryUpdateDTO(

        @Schema(description = "New stock quantity (must be >= 0)", example = "75")
        @NotNull(message = "Quantity must not be null")
        @Min(value = 0, message = "Quantity must not be negative")
        Integer quantity
) {
}
