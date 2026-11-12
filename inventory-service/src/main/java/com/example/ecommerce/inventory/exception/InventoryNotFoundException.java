package com.example.ecommerce.inventory.exception;

public class InventoryNotFoundException extends RuntimeException {
    public InventoryNotFoundException(String skuCode) {
        super("Inventory item not found with skuCode: " + skuCode);
    }
}
