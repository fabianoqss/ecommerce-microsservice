package com.example.ecommerce.inventory_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(name = "InventoryReserveRequestDTO", description = "Itens a reservar ou liberar em estoque")
public record InventoryReserveRequestDTO(

        @Schema(description = "Itens do pedido")
        @NotEmpty(message = "A lista de itens não pode ser vazia")
        @Valid
        List<InventoryItemDTO> items
) {
}
