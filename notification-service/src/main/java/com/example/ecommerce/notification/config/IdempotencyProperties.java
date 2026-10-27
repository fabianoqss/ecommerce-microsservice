package com.example.ecommerce.notification.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.notification.idempotency")
public record IdempotencyProperties(
        @NotNull @DurationMin(millis = 1) @DefaultValue("30s") Duration processingTtl,
        @NotNull @DurationMin(millis = 1) @DefaultValue("7d") Duration processedTtl
) {
}
