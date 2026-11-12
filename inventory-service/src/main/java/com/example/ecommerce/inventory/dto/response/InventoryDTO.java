package com.example.ecommerce.inventory.dto.response;

import com.example.ecommerce.inventory.domain.entity.Inventory;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "InventoryDTO", description = "Product inventory data")
public class InventoryDTO {

    @Schema(description = "Product SKU code", example = "KEYBOARD-MECH-001")
    private String skuCode;
    @Schema(description = "Quantity available in stock", example = "50")
    private Integer quantity;
    @Schema(description = "Whether the product is in stock", example = "true", accessMode = Schema.AccessMode.READ_ONLY)
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
