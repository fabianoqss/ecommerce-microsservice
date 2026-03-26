package com.example.ecommerce.order_service.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "OrderRequestDTO", description = "Dados para criação de um pedido")
public record OrderRequestDTO(
        @Schema(description = "ID do usuário que está fazendo o pedido", example = "user-123")
        String userId,
        @Schema(description = "Lista de itens do pedido")
        List<OrderItemRequestDTO> items
) {
}
