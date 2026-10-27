package com.example.ecommerce.order.dto.client.product;

import java.math.BigDecimal;

public record ProductDTO(
        String id,
        String name,
        BigDecimal price
) {
}
