package com.ecommerce.product_service.dto;

import com.ecommerce.product_service.entities.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(name = "ProductDTO", description = "Dados de um produto")
public class ProductDTO {

    @Schema(description = "ID gerado pelo MongoDB", example = "664f1b2e3a4b5c6d7e8f9a0b", accessMode = Schema.AccessMode.READ_ONLY)
    private String id;

    @Schema(description = "Nome do produto", example = "Teclado Mecânico")
    @NotBlank(message = "O Nome do Produto não pode ser nulo ou vazio")
    private String name;

    @Schema(description = "Descrição do produto (mínimo 10 caracteres)", example = "Teclado mecânico com switches blue e RGB")
    @NotBlank(message = "O Produto Precisa ter uma descrição")
    @Size(min = 10)
    private String description;

    @Schema(description = "Preço do produto (deve ser positivo)", example = "349.90")
    @Positive(message = "O Nome do Preco não pode ser negativo ")
    private BigDecimal price;

    @Schema(description = "Categoria do produto", example = "Periféricos")
    @NotNull(message = "O nome da Categoria não pode ser nulo")
    private String category;

    @Schema(description = "URL da imagem do produto", example = "https://example.com/imagens/teclado.jpg")
    private String imageUrl;

    public ProductDTO() {
    }

    public ProductDTO(String name, String description, BigDecimal price, String category, String imageUrl) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.imageUrl = imageUrl;
    }

    public ProductDTO (Product entity){
        id = entity.getId();
        name = entity.getName();
        description = entity.getDescription();
        price = entity.getPrice();
        category = entity.getCategory();
        imageUrl = entity.getImageUrl();
    }


    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }


    public String getDescription() {
        return description;
    }



    public BigDecimal getPrice() {
        return price;
    }



    public String getCategory() {
        return category;
    }

    public String getImageUrl() {
        return imageUrl;
    }
}
