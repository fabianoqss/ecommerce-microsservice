package com.example.ecommerce.order.dto.response;

public record CreateOrderResult(
        OrderResponseDTO orderResponseDTO,
        boolean replayed
) {

}
