package com.example.ecommerce.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void mapsRealmRolesAndScopesAndUsesSubjectAsName() {
        Jwt jwt = jwt(Map.of(
                "sub", "user-sub",
                "preferred_username", "nome-mutavel",
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

    @ParameterizedTest
    @MethodSource("malformedRealmAccessClaims")
    void malformedRealmAccessIsIgnored(Map<String, Object> claims) {
        var authentication = converter.convert(jwt(claims));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    static Stream<Map<String, Object>> malformedRealmAccessClaims() {
        return Stream.of(
                Map.of("sub", "x"),
                Map.of("sub", "x", "realm_access", "ADMIN"),
                Map.of("sub", "x", "realm_access", List.of("ADMIN")),
                Map.of("sub", "x", "realm_access", Map.of()),
                Map.of("sub", "x", "realm_access", Map.of("roles", "ADMIN")),
                Map.of("sub", "x", "realm_access", Map.of("roles", Map.of("ADMIN", true))),
                Map.of("sub", "x", "realm_access", Map.of("roles", List.of())));
    }

    @Test
    void ignoresNonStringRolesAndDeduplicatesAuthorities() {
        var authentication = converter.convert(jwt(Map.of(
                "sub", "user-sub",
                "scope", "openid openid",
                "realm_access", Map.of("roles", Arrays.asList("ADMIN", "ADMIN", 42, null, true, "CUSTOMER")))));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CUSTOMER", "SCOPE_openid");
    }

    @Test
    void preservesScpAuthoritiesWithoutGrantingClientRoles() {
        var authentication = converter.convert(jwt(Map.of(
                "sub", "svc",
                "scp", List.of("inventory:reserve"),
                "resource_access", Map.of("client", Map.of("roles", List.of("ADMIN"))))));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_inventory:reserve");
    }

    @Test
    void extractsUnprefixedRealmRolesFromIdTokenClaimsForBff() {
        var roles = KeycloakJwtAuthenticationConverter.realmRoles(Map.of(
                "realm_access", Map.of("roles", Arrays.asList("ADMIN", "ADMIN", "CUSTOMER", 42, null))));

        assertThat(roles).containsExactlyInAnyOrder("ADMIN", "CUSTOMER");
    }

    @ParameterizedTest
    @MethodSource("malformedRealmAccessClaims")
    void ignoresMalformedRealmRolesInIdTokenClaims(Map<String, Object> claims) {
        assertThat(KeycloakJwtAuthenticationConverter.realmRoles(claims)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("malformedClaims")
    void ignoresMalformedRolesWithoutDroppingScopes(Map<String, Object> claims) {
        var authentication = converter.convert(Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("svc").claim("scope", "inventory:reserve")
                .claims(c -> c.putAll(claims)).build());

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_inventory:reserve");
        assertThat(KeycloakJwtAuthenticationConverter.realmRoles(claims)).isEmpty();
    }

    private static Stream<Map<String, Object>> malformedClaims() {
        return Stream.of(
                Map.of(),
                Map.of("realm_access", "ADMIN"),
                Map.of("realm_access", List.of("ADMIN")),
                Map.of("realm_access", Map.of()),
                Map.of("realm_access", Map.of("roles", "ADMIN")),
                Map.of("realm_access", Map.of("roles", List.of(42, true))),
                Map.of("realm_access", Map.of("roles", List.of())));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
    }
}
