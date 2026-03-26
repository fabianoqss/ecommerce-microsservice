package com.example.ecommerce.order_service.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(name = "OrderItemResponseDTO", description = "Item de resposta de um pedido")
public record OrderItemResponseDTO(
        @Schema(description = "ID do produto", example = "664f1b2e3a4b5c6d7e8f9a0b")
        String productId,
        @Schema(description = "Nome do produto no momento da compra", example = "Teclado Mecânico")
        String productName,
        @Schema(description = "Quantidade comprada", example = "2")
        Integer quantity,
        @Schema(description = "Preço unitário no momento da compra", example = "349.90")
        BigDecimal priceAtPurchase) {
}
