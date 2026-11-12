package com.example.ecommerce.inventory.dto.request;

import com.example.ecommerce.inventory.dto.validation.SkuCodeRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(name = "InventoryItemDTO", description = "Inventory item to reserve or release")
public record InventoryItemDTO(

        @Schema(description = SkuCodeRules.DESCRIPTION, example = SkuCodeRules.EXAMPLE)
        @NotBlank(message = "skuCode is required")
        @Pattern(regexp = SkuCodeRules.PATTERN, message = SkuCodeRules.MESSAGE)
        String skuCode,

        @Schema(description = "Quantity (between 1 and 10000)", example = "2")
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be greater than or equal to 1")
        @Max(value = 10000, message = "quantity must be less than or equal to 10000")
        Integer quantity
) {
}
