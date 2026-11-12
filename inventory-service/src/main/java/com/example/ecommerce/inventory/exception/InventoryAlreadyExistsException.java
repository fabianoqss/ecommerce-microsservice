package com.example.ecommerce.inventory.exception;

public class InventoryAlreadyExistsException extends RuntimeException {
    public InventoryAlreadyExistsException(String skuCode, Throwable cause) {
        super("Inventory already exists for skuCode: " + skuCode, cause);
    }
}
