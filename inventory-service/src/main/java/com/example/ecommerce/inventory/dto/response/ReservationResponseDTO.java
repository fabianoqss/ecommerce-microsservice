package com.example.ecommerce.inventory.dto.response;

import com.example.ecommerce.inventory.domain.ReservationStatus;
import com.example.ecommerce.inventory.dto.request.InventoryItemDTO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "ReservationResponseDTO", description = "Estado da reserva no livro-razão de estoque")
public record ReservationResponseDTO(

        @Schema(description = "Identificador da reserva (orderId)")
        UUID id,

        @Schema(description = "Estado atual da reserva (RESERVED ou RELEASED)")
        ReservationStatus status,

        @Schema(description = "Itens normalizados da reserva, ordenados por skuCode")
        List<InventoryItemDTO> items,

        @Schema(description = "Instante de criação da reserva/lápide")
        Instant createdAt,

        @Schema(description = "Instante da última transição de estado")
        Instant updatedAt
) {
}
