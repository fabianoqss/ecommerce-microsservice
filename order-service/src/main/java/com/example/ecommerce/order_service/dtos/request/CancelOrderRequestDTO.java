package com.example.ecommerce.order_service.dtos.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "CancelOrderRequestDTO", description = "Dados opcionais do cancelamento de um pedido")
public record CancelOrderRequestDTO(
        @Schema(description = "Motivo do cancelamento (auditoria)", example = "Comprei o item errado")
        @Size(max = 255)
        String reason
) {
}
