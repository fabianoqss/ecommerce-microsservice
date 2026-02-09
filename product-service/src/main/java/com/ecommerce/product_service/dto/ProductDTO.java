package com.ecommerce.product_service.dto;

import com.ecommerce.product_service.entities.Product;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class ProductDTO {

    @NotBlank(message = "O Nome do Produto não pode ser nulo ou vazio")
    private String name;

    @NotBlank(message = "O Produto Precisa ter uma descrição")
    @Size(min = 10)
    private String description;

    @Positive(message = "O Nome do Preco não pode ser negativo ")
    private Double price;

    @NotNull(message = "O nome da Categoria não pode ser nulo")
    private String category;


    public ProductDTO() {
    }


    public ProductDTO(String name, String description, Double price, String category) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
    }

    public ProductDTO (Product entity){
        name = entity.getName();
        description = entity.getDescription();
        price = entity.getPrice();
        category = entity.getCategory();
    }


    public String getName() {
        return name;
    }


    public String getDescription() {
        return description;
    }



    public Double getPrice() {
        return price;
    }



    public String getCategory() {
        return category;
    }


}
