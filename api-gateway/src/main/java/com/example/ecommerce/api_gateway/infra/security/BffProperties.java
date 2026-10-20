package com.example.ecommerce.api_gateway.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param postLoginRedirectUri  para onde o navegador vai depois do login (frontend ou /bff/me)
 * @param postLogoutRedirectUri para onde o IdP devolve depois do logout; vazio = página de logout do IdP
 * @param issuerUri         issuer do provedor OIDC como o navegador o enxerga (URL pública)
 * @param internalIssuerUri o mesmo issuer visto de dentro da rede (back-channel do gateway)
 */
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
