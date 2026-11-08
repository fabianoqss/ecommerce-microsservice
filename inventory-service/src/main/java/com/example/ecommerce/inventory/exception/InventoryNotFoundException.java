package com.example.ecommerce.inventory.exception;

public class InventoryNotFoundException extends RuntimeException {
    public InventoryNotFoundException(String skuCode) {
        super("Item de estoque não encontrado com skuCode: " + skuCode);
    }
}
