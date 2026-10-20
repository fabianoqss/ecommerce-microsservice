package com.example.ecommerce.api_gateway.infra.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Regras grossas de borda, comuns às cadeias Bearer e de sessão. As regras finas (roles, dono do
 * pedido) ficam em cada microsserviço, que valida o token sozinho.
 */
final class GatewayAuthorizationRules {

    private GatewayAuthorizationRules() {
    }

    static AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry apply(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        return authorize
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/error").permitAll()
                // Contrato interno order → inventory; nunca exposto pela borda.
                .requestMatchers("/api/inventory/reserve", "/api/inventory/release").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/product", "/api/product/**", "/api/inventory", "/api/inventory/**").permitAll()
                .requestMatchers("/api/**").authenticated();
    }
}
