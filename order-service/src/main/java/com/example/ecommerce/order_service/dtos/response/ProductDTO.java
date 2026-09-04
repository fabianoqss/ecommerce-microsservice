package com.example.ecommerce.order_service.dtos.response;

import java.math.BigDecimal;

public record ProductDTO(
        String id,
        String name,
        BigDecimal price
) {
}
