package com.example.ecommerce.order_service.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "OrderResponseDTO", description = "Dados de resposta de um pedido")
public record OrderResponseDTO (
        @Schema(description = "ID do pedido gerado pelo banco", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
        Long id,
        @Schema(description = "Status do pedido", example = "PLACED")
        String status,
        @Schema(description = "Valor total do pedido", example = "699.80")
        BigDecimal totalValue,
        @Schema(description = "Data e hora da criação do pedido", example = "2026-03-26T14:30:00")
        LocalDateTime orderTime,
        @Schema(description = "Itens do pedido")
        List<OrderItemResponseDTO> items
) {
}
