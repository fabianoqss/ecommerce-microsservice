package com.example.ecommerce.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converte o JWT do Keycloak em autenticação do Spring: scopes viram SCOPE_*, realm roles
 * (claim realm_access.roles) viram ROLE_*, e o nome da autenticação é o sub (UUID imutável).
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(scopesConverter.convert(jwt));
        for (String role : realmRoles(jwt.getClaims())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /**
     * Também atende ao BFF, que lê roles sem prefixo dos claims do ID token.
     * Formatos inesperados não concedem autoridades.
     */
    public static Set<String> realmRoles(Map<String, Object> claims) {
        Set<String> roles = new HashSet<>();
        if (claims.get("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> names) {
            for (Object name : names) {
                if (name instanceof String role) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }
}
