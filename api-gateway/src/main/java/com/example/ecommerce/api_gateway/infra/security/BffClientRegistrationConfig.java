package com.example.ecommerce.api_gateway.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import java.util.Map;

@Configuration
public class BffClientRegistrationConfig {

    /**
     * Declarado em Java para informar issuer e end_session_endpoint sem discovery na subida:
     * o navegador usa a URL pública do Keycloak, o gateway (back-channel) usa a URL interna.
     * O registrationId "keycloak" faz parte do redirect_uri cadastrado no client ecommerce-bff.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(BffProperties properties) {
        String publicEndpoints = properties.issuerUri() + "/protocol/openid-connect";
        String internalEndpoints = properties.internalIssuerUri() + "/protocol/openid-connect";

        ClientRegistration keycloak = ClientRegistration.withRegistrationId("keycloak")
                .clientName("Keycloak")
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .issuerUri(properties.issuerUri())
                .authorizationUri(publicEndpoints + "/auth")
                .tokenUri(internalEndpoints + "/token")
                .jwkSetUri(internalEndpoints + "/certs")
                .userInfoUri(internalEndpoints + "/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .providerConfigurationMetadata(Map.of("end_session_endpoint", publicEndpoints + "/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(keycloak);
    }

    /** Tokens guardados na sessão HTTP (Redis): sobrevivem a restart e são compartilhados entre instâncias. */
    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository clientRegistrations,
                                                                 OAuth2AuthorizedClientRepository authorizedClients) {
        var manager = new DefaultOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build());
        return manager;
    }
}
