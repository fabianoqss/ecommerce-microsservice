package com.example.ecommerce.gateway.security.jwt;

import com.example.ecommerce.common.security.KeycloakJwtAuthenticationConverter;

import java.util.Map;
import java.util.Set;

public final class KeycloakRoles {

    private KeycloakRoles() {
    }

    public static Set<String> fromClaims(Map<String, Object> claims) {
        return KeycloakJwtAuthenticationConverter.realmRoles(claims);
    }
}
