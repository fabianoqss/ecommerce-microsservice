package com.example.ecommerce.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "OrderRequestDTO", description = "Dados para criação de um pedido")
public record OrderRequestDTO(
        @Schema(description = "Lista de itens do pedido")
        @NotEmpty(message = "A lista de itens não pode ser vazia")
        @Size(max = 50, message = "O pedido deve ter no máximo 50 itens")
        @Valid
        List<@NotNull(message = "O item não pode ser nulo") OrderItemRequestDTO> items
) {
}
