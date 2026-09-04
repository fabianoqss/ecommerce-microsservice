package com.example.ecommerce.order_service.dtos.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(name = "OrderRequestDTO", description = "Dados para criação de um pedido")
public record OrderRequestDTO(
        @Schema(description = "Lista de itens do pedido")
        @NotEmpty(message = "A lista de itens não pode ser vazia")
        @Valid
        List<OrderItemRequestDTO> items
) {
}
