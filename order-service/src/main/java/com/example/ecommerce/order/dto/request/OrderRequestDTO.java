package com.example.ecommerce.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "OrderRequestDTO", description = "Data to create an order")
public record OrderRequestDTO(
        @Schema(description = "Order item list")
        @NotEmpty(message = "Item list must not be empty")
        @Size(max = 50, message = "Order must contain at most 50 items")
        @Valid
        List<@NotNull(message = "Item must not be null") OrderItemRequestDTO> items
) {
}
