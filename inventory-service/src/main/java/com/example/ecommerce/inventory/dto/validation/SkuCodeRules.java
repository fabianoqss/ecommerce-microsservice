package com.example.ecommerce.inventory.dto.validation;

public final class SkuCodeRules {

    public static final String PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    public static final String MESSAGE = "skuCode deve ter de 1 a 64 letras, números, _ ou -";
    public static final String DESCRIPTION = "ID do produto no product-service, usado como chave do estoque";
    public static final String EXAMPLE = "664f1b2e3a4b5c6d7e8f9a0b";

    private SkuCodeRules() {
    }
}
