package com.example.ecommerce.order_service.dtos.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrderItemRequestDTO", description = "Item de um pedido")
public record OrderItemRequestDTO(
        @Schema(description = "ID do produto (MongoDB)", example = "664f1b2e3a4b5c6d7e8f9a0b")
        String productId,
        @Schema(description = "Quantidade do produto", example = "2")
        Integer quantity
) {
}
