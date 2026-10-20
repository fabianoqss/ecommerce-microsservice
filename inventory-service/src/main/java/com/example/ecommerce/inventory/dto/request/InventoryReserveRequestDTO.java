package com.example.ecommerce.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "InventoryReserveRequestDTO", description = "Itens a reservar ou liberar em estoque")
public record InventoryReserveRequestDTO(

        @Schema(description = "Itens do pedido (entre 1 e 50 itens)")
        @NotEmpty(message = "A lista de itens não pode ser vazia")
        @Size(min = 1, max = 50, message = "A lista de itens deve conter entre 1 e 50 itens")
        @Valid
        List<@NotNull(message = "Item da lista não pode ser nulo") InventoryItemDTO> items
) {
}
