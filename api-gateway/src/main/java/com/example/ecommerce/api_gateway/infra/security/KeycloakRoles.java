package com.example.ecommerce.api_gateway.infra.security;

import java.util.Map;
import java.util.Set;

public final class KeycloakRoles {

    private KeycloakRoles() {
    }

    /** Realm roles (claim realm_access.roles) de um access token ou ID token do Keycloak. */
    public static Set<String> fromClaims(Map<String, Object> claims) {
        return KeycloakJwtAuthenticationConverter.realmRoles(claims);
    }
}
