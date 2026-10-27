package com.example.ecommerce.gateway.bff;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ecommerce.bff")
public record BffProperties(
        String postLoginRedirectUri,
        String postLogoutRedirectUri,
        String issuerUri,
        String internalIssuerUri,
        String clientId,
        String clientSecret
) {
}
