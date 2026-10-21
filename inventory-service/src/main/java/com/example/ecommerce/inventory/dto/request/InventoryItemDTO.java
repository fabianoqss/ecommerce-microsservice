package com.example.ecommerce.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(name = "InventoryItemDTO", description = "Item de estoque a reservar/liberar")
public record InventoryItemDTO(

        @Schema(description = "Código SKU do produto", example = "TECLADO-MECH-001")
        @NotBlank(message = "skuCode é obrigatório")
        String skuCode,

        @Schema(description = "Quantidade (entre 1 e 10000)", example = "2")
        @NotNull(message = "quantity é obrigatório")
        @Min(value = 1, message = "quantity deve ser maior ou igual a 1")
        @Max(value = 10000, message = "quantity deve ser menor ou igual a 10000")
        Integer quantity
) {
}
