package com.example.ecommerce.inventory_service.dto;

import com.example.ecommerce.inventory_service.entities.Inventory;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "InventoryDTO", description = "Dados de estoque de um produto")
public class InventoryDTO {

    @Schema(description = "Código SKU do produto", example = "TECLADO-MECH-001")
    private String skuCode;
    @Schema(description = "Quantidade disponível em estoque", example = "50")
    private Integer quantity;
    @Schema(description = "Indica se o produto está em estoque", example = "true", accessMode = Schema.AccessMode.READ_ONLY)
    private boolean inStock;

    public InventoryDTO(Inventory entity){
        skuCode = entity.getSkuCode();
        quantity = entity.getQuantity();
        inStock = quantity > 0 ;
    }


    public String getSkuCode() {
        return skuCode;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public boolean isInStock() {
        return inStock;
    }
}
