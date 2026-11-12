package com.example.ecommerce.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(name = "OrderItemResponseDTO", description = "Order item response data")
public record OrderItemResponseDTO(
        @Schema(description = "Product ID", example = "664f1b2e3a4b5c6d7e8f9a0b")
        String productId,
        @Schema(description = "Product name at the time of purchase", example = "Mechanical Keyboard")
        String productName,
        @Schema(description = "Quantity purchased", example = "2")
        Integer quantity,
        @Schema(description = "Unit price at the time of purchase", example = "349.90")
        BigDecimal priceAtPurchase) {
}
