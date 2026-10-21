package com.example.ecommerce.order.dto.response;

import java.math.BigDecimal;

public record ProductDTO(
        String id,
        String name,
        BigDecimal price
) {
}
