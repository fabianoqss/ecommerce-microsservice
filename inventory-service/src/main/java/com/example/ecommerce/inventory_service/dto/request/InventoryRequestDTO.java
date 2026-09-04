package com.example.ecommerce.inventory_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(name = "InventoryRequestDTO", description = "Dados para inserção de um item no estoque")
public record InventoryRequestDTO(

        @Schema(description = "Código SKU do produto", example = "TECLADO-MECH-001")
        @NotBlank(message = "SKU Code não pode ser vazio")
        String skuCode,

        @Schema(description = "Quantidade inicial em estoque (deve ser >= 0)", example = "100")
        @NotNull(message = "Quantidade não pode ser nula")
        @Min(value = 0, message = "Quantidade não pode ser negativa")
        Integer quantity
) {
}
