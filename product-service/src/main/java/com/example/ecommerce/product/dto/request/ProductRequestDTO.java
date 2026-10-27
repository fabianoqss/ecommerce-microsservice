package com.example.ecommerce.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(name = "ProductRequestDTO", description = "Dados para criar ou atualizar um produto")
public record ProductRequestDTO(
        @Schema(description = "Nome do produto", example = "Teclado Mecânico")
        @NotBlank(message = "O Nome do Produto não pode ser nulo ou vazio")
        @Size(max = 255, message = "O nome do produto deve ter no máximo 255 caracteres")
        String name,

        @Schema(description = "Descrição do produto (mínimo 10 caracteres)", example = "Teclado mecânico com switches blue e RGB")
        @NotBlank(message = "O Produto Precisa ter uma descrição")
        @Size(min = 10)
        String description,

        @Schema(description = "Preço do produto (deve ser positivo)", example = "349.90")
        @NotNull(message = "O preço do produto não pode ser nulo")
        @Digits(integer = 17, fraction = 2, message = "O preço do produto deve ter no máximo 17 dígitos inteiros e 2 casas decimais")
        @Positive(message = "O preço do produto deve ser positivo")
        BigDecimal price,

        @Schema(description = "Categoria do produto", example = "Periféricos")
        @NotNull(message = "O nome da Categoria não pode ser nulo")
        String category,

        @Schema(description = "URL da imagem do produto", example = "https://example.com/imagens/teclado.jpg")
        String imageUrl
) {
}
