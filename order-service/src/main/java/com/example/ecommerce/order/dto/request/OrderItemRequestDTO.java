package com.example.ecommerce.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@Schema(name = "OrderItemRequestDTO", description = "Item de um pedido")
public record OrderItemRequestDTO(
        @Schema(description = "ID do produto (MongoDB)", example = "664f1b2e3a4b5c6d7e8f9a0b")
        @NotBlank(message = "productId é obrigatório")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "productId deve ter de 1 a 64 letras, números, _ ou -")
        String productId,
        @Schema(description = "Quantidade do produto", example = "2")
        @NotNull(message = "quantity é obrigatório")
        @Positive(message = "quantity deve ser maior que zero")
        @Max(value = 10000, message = "quantity deve ser menor ou igual a 10000")
        Integer quantity
) {
}
