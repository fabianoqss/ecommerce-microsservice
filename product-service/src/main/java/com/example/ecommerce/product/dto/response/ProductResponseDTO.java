package com.example.ecommerce.product.dto.response;

import com.example.ecommerce.product.domain.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(name = "ProductResponseDTO", description = "Dados de um produto")
public record ProductResponseDTO(
        @Schema(description = "ID gerado pelo MongoDB", example = "664f1b2e3a4b5c6d7e8f9a0b")
        String id,

        @Schema(description = "Nome do produto", example = "Teclado Mecânico")
        String name,

        @Schema(description = "Descrição do produto", example = "Teclado mecânico com switches blue e RGB")
        String description,

        @Schema(description = "Preço do produto", example = "349.90")
        BigDecimal price,

        @Schema(description = "Categoria do produto", example = "Periféricos")
        String category,

        @Schema(description = "URL da imagem do produto", example = "https://example.com/imagens/teclado.jpg")
        String imageUrl
) {

    public static ProductResponseDTO from(Product product) {
        return new ProductResponseDTO(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getCategory(), product.getImageUrl());
    }
}
