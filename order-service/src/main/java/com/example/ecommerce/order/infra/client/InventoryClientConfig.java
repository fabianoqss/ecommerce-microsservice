package com.example.ecommerce.order.infra.client;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

// Sem @Configuration: o interceptor pertence apenas ao contexto Feign do inventory.
public class InventoryClientConfig {

    @Bean
    public RequestInterceptor inventoryAuthorizationInterceptor(OAuth2AuthorizedClientManager authorizedClientManager) {
        return template -> {
            // Identidade estável mantém o cache de client_credentials independente do usuário logado.
            var request = OAuth2AuthorizeRequest.withClientRegistrationId("order-service")
                    .principal("order-service")
                    .build();
            var client = authorizedClientManager.authorize(request);
            if (client == null || client.getAccessToken() == null || client.getAccessToken().getTokenValue().isBlank()) {
                throw new OAuth2AuthorizationException(new OAuth2Error("service_token_unavailable",
                        "Não foi possível obter o token de serviço para o inventory-service", null));
            }
            template.removeHeader(HttpHeaders.AUTHORIZATION);
            template.header(HttpHeaders.AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue());
        };
    }
}
