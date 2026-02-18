package com.example.ecommerce.order_service.dtos;

import java.math.BigDecimal;

public record OrderItemResponseDTO(
        String productId,
        String productName,
        Integer quantity,
        BigDecimal priceAtPurchase) {
}
