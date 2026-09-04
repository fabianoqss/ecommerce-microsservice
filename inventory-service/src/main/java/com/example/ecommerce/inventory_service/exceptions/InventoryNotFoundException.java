package com.example.ecommerce.inventory_service.exceptions;

public class InventoryNotFoundException extends RuntimeException {
    public InventoryNotFoundException(Long id) {
        super("Item de estoque não encontrado com id: " + id);
    }
}
