package com.example.ecommerce.inventory_service.dto;

public class InventoryDTO {

    private Long id;
    private String skuCode;
    private Integer quantity;




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
