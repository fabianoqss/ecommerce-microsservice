package com.example.ecommerce.inventory.dto.request;

import com.example.ecommerce.inventory.dto.validation.SkuCodeRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(name = "InventoryRequestDTO", description = "Dados para inserção de um item no estoque")
public record InventoryRequestDTO(

        @Schema(description = SkuCodeRules.DESCRIPTION, example = SkuCodeRules.EXAMPLE)
        @NotBlank(message = "SKU Code não pode ser vazio")
        @Pattern(regexp = SkuCodeRules.PATTERN, message = SkuCodeRules.MESSAGE)
        String skuCode,

        @Schema(description = "Quantidade inicial em estoque (deve ser >= 0)", example = "100")
        @NotNull(message = "Quantidade não pode ser nula")
        @Min(value = 0, message = "Quantidade não pode ser negativa")
        Integer quantity
) {
}
