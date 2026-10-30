package com.example.ecommerce.inventory.exception;

public class InventoryAlreadyExistsException extends RuntimeException {
    public InventoryAlreadyExistsException(String skuCode, Throwable cause) {
        super("Já existe estoque cadastrado para o skuCode: " + skuCode, cause);
    }
}
