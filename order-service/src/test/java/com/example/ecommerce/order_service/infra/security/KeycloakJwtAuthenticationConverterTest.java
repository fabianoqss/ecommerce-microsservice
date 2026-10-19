package com.example.ecommerce.order_service.infra.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void mapsRealmRolesAndScopesAndUsesSubjectAsName() {
        Jwt jwt = jwt(Map.of(
                "sub", "user-sub",
                "scope", "openid inventory:reserve",
                "realm_access", Map.of("roles", List.of("ADMIN", "CUSTOMER"))));

        var authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("user-sub");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CUSTOMER", "SCOPE_openid", "SCOPE_inventory:reserve");
    }

    @Test
    void missingRealmAccessYieldsOnlyScopes() {
        var authentication = converter.convert(jwt(Map.of("sub", "svc", "scope", "inventory:reserve")));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_inventory:reserve");
    }

    @Test
    void malformedRealmAccessIsIgnored() {
        var authentication = converter.convert(jwt(Map.of(
                "sub", "x",
                "realm_access", Map.of("roles", "ADMIN"))));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
    }
}
