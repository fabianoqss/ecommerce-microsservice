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
        @Min(value = 1, message = "O limite de quantidade por produto deve ser maior que zero")
        @Max(value = 10000, message = "O limite de quantidade por produto deve ser menor ou igual a 10000")
        int maxQuantityPerProduct
) {
}
