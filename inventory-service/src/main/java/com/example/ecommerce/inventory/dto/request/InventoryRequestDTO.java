package com.example.ecommerce.inventory.dto.request;

import com.example.ecommerce.inventory.dto.validation.SkuCodeRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(name = "InventoryRequestDTO", description = "Data to add an inventory item")
public record InventoryRequestDTO(

        @Schema(description = SkuCodeRules.DESCRIPTION, example = SkuCodeRules.EXAMPLE)
        @NotBlank(message = "SKU code must not be blank")
        @Pattern(regexp = SkuCodeRules.PATTERN, message = SkuCodeRules.MESSAGE)
        String skuCode,

        @Schema(description = "Initial stock quantity (must be >= 0)", example = "100")
        @NotNull(message = "Quantity must not be null")
        @Min(value = 0, message = "Quantity must not be negative")
        Integer quantity
) {
}
