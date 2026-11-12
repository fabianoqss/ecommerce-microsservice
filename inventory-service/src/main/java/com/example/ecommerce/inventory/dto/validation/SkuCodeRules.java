package com.example.ecommerce.inventory.dto.validation;

public final class SkuCodeRules {

    public static final String PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    public static final String MESSAGE = "skuCode must contain 1 to 64 letters, digits, _ or -";
    public static final String DESCRIPTION = "Product ID in product-service, used as the inventory key";
    public static final String EXAMPLE = "664f1b2e3a4b5c6d7e8f9a0b";
    public static final int MAX_LOOKUP_SIZE = 50;
    public static final String LOOKUP_SIZE_MESSAGE = "Provide 1 to 50 skuCode values per query";

    private SkuCodeRules() {
    }
}
