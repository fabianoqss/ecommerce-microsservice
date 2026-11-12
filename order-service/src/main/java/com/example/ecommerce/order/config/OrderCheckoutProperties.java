package com.example.ecommerce.order.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ecommerce.order.checkout")
public record OrderCheckoutProperties(
        @DefaultValue("10000")
        @Min(value = 1, message = "Quantity limit per product must be greater than zero")
        @Max(value = 10000, message = "Quantity limit per product must be less than or equal to 10000")
        int maxQuantityPerProduct
) {
}
