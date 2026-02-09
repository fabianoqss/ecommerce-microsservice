package com.example.ecommerce.inventory_service.dto;

import com.example.ecommerce.inventory_service.entities.Inventory;

public class InventoryDTO {

    private String skuCode;
    private Integer quantity;
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
}
