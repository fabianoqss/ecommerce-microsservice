package com.example.ecommerce.order_service.dtos.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(name = "OrderItemRequestDTO", description = "Item de um pedido")
public record OrderItemRequestDTO(
        @Schema(description = "ID do produto (MongoDB)", example = "664f1b2e3a4b5c6d7e8f9a0b")
        @NotBlank(message = "productId é obrigatório")
        String productId,
        @Schema(description = "Quantidade do produto", example = "2")
        @NotNull(message = "quantity é obrigatório")
        @Min(value = 1, message = "quantity deve ser maior ou igual a 1")
        Integer quantity
) {
}
