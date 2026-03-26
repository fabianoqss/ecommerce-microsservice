package com.example.ecommerce.inventory_service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "InventoryUpdateDTO", description = "Dados para atualização de quantidade em estoque")
public record InventoryUpdateDTO(

        @Schema(description = "Nova quantidade em estoque (deve ser >= 0)", example = "75")
        @NotNull(message = "Quantidade não pode ser nula")
        @Min(value = 0, message = "Quantidade não pode ser negativa")
        Integer quantity
) {
}
