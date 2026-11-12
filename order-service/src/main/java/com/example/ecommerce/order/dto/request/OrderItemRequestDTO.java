package com.example.ecommerce.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@Schema(name = "OrderItemRequestDTO", description = "Order item")
public record OrderItemRequestDTO(
        @Schema(description = "Product ID (MongoDB)", example = "664f1b2e3a4b5c6d7e8f9a0b")
        @NotBlank(message = "productId is required")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "productId must contain 1 to 64 letters, digits, _ or -")
        String productId,
        @Schema(description = "Product quantity", example = "2")
        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than zero")
        @Max(value = 10000, message = "quantity must be less than or equal to 10000")
        Integer quantity
) {
}
