package com.example.ecommerce.inventory_service.dto;

import com.example.ecommerce.inventory_service.entities.Inventory;

public class InventoryDTO {

    private Long id;
    private String skuCode;
    private Integer quantity;



    public InventoryDTO(Inventory entity){
        id = entity.getId();
        skuCode = entity.getSkuCode();
        quantity = entity.getQuantity();
    }

    public Long getId() {
        return id;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public Integer getQuantity() {
        return quantity;
    }
}
