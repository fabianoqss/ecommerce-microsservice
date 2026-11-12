package com.example.ecommerce.product.dto.response;

import com.example.ecommerce.product.domain.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(name = "ProductResponseDTO", description = "Product data")
public record ProductResponseDTO(
        @Schema(description = "MongoDB-generated ID", example = "664f1b2e3a4b5c6d7e8f9a0b")
        String id,

        @Schema(description = "Product name", example = "Mechanical Keyboard")
        String name,

        @Schema(description = "Product description", example = "Mechanical keyboard with blue switches and RGB")
        String description,

        @Schema(description = "Product price", example = "349.90")
        BigDecimal price,

        @Schema(description = "Product category", example = "Peripherals")
        String category,

        @Schema(description = "Product image URL", example = "https://example.com/images/keyboard.jpg")
        String imageUrl,

        @Schema(description = "Product version; the same value is returned in the ETag and must be sent in If-Match for PUT/DELETE", example = "3")
        Long version
) {

    public static ProductResponseDTO from(Product product) {
        return new ProductResponseDTO(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getCategory(), product.getImageUrl(), product.getVersion());
    }
}
