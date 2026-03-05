package com.example.ecommerce.inventory_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InventoryRequestDTO(

        @NotBlank(message = "SKU Code não pode ser vazio")
        String skuCode,

        @NotNull(message = "Quantidade não pode ser nula")
        @Min(value = 0, message = "Quantidade não pode ser negativa")
        Integer quantity
) {
}
