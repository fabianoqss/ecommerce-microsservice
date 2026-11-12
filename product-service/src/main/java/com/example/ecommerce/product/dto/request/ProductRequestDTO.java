package com.example.ecommerce.product.dto.request;

import com.example.ecommerce.product.dto.validation.HttpsUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(name = "ProductRequestDTO", description = "Data to create or update a product")
public record ProductRequestDTO(
        @Schema(description = "Product name (maximum 255 characters)", example = "Mechanical Keyboard")
        @NotBlank(message = "Product name must not be null or blank")
        @Size(max = 255, message = "Product name must be at most 255 characters long")
        String name,

        @Schema(description = "Product description (between 10 and 2000 characters)", example = "Mechanical keyboard with blue switches and RGB")
        @NotBlank(message = "Product description is required")
        @Size(min = 10, max = 2000, message = "Description must be between 10 and 2000 characters long")
        String description,

        @Schema(description = "Product price (must be positive)", example = "349.90")
        @NotNull(message = "Product price must not be null")
        @Digits(integer = 17, fraction = 2, message = "Product price must have at most 17 integer digits and 2 decimal places")
        @Positive(message = "Product price must be positive")
        BigDecimal price,

        @Schema(description = "Product category (maximum 100 characters)", example = "Peripherals")
        @NotBlank(message = "Category name must not be null or blank")
        @Size(max = 100, message = "Product category must be at most 100 characters long")
        String category,

        @Schema(description = "Absolute HTTPS URL of the product image (optional, maximum 2048 characters)", example = "https://example.com/images/keyboard.jpg")
        @Size(max = 2048, message = "Image URL must be at most 2048 characters long")
        @HttpsUrl
        String imageUrl
) {
}
