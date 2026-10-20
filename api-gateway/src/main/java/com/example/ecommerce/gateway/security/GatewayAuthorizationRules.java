package com.example.ecommerce.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Regras grossas de borda, comuns às cadeias Bearer e de sessão. As regras finas (roles, dono do
 * pedido) ficam em cada microsserviço, que valida o token sozinho.
 */
final class GatewayAuthorizationRules {

    private GatewayAuthorizationRules() {
    }

    static RequestMatcher managementPort(Environment environment) {
        return request -> {
            HttpServletRequest connection = request;
            // Wrappers de X-Forwarded-* podem reescrever portas; usamos a conexão do container.
            while (connection instanceof HttpServletRequestWrapper wrapper) {
                connection = (HttpServletRequest) wrapper.getRequest();
            }
            int configuredPort = environment.getProperty("management.server.port", Integer.class, 8090);
            int actualPort = environment.getProperty("local.management.port", Integer.class, configuredPort);
            int serverPort = environment.getProperty("local.server.port", Integer.class,
                    environment.getProperty("server.port", Integer.class, 8080));
            // Porta de gerenciamento igual à da aplicação seria pública: nesse caso nada é liberado.
            return actualPort > 0 && actualPort != serverPort && connection.getLocalPort() == actualPort;
        };
    }

    static AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry apply(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        return authorize
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/error").permitAll()
                // Contrato interno order → inventory; nunca exposto pela borda.
                .requestMatchers("/api/inventory/reserve", "/api/inventory/release",
                        "/api/inventory/reservations", "/api/inventory/reservations/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/product", "/api/product/**", "/api/inventory", "/api/inventory/**").permitAll()
                .requestMatchers("/api/**").authenticated();
    }
}
