package com.example.ecommerce.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "CancelOrderRequestDTO", description = "Optional order cancellation data")
public record CancelOrderRequestDTO(
        @Schema(description = "Cancellation reason (audit)", example = "I ordered the wrong item")
        @Size(max = 255)
        String reason
) {
}
