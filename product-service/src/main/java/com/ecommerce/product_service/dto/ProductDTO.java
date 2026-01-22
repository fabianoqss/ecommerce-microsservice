package com.ecommerce.product_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProductDTO(@NotBlank(message = "O Nome do Produto não pode ser nulo ou vazio") String name,
                         @NotBlank(message = "O Produto Precisa ter uma descrição") @Size(min = 10) String description,
                         @Positive(message = "O Nome do Preco não pode ser negativo ") Double price,
                         @NotNull (message = "O nome da Categoria não pode ser nulo") String category ) {

}
