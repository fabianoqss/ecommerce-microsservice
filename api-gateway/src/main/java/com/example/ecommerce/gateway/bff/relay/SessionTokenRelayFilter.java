package com.example.ecommerce.gateway.bff.relay;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * BFF: para requisições /api/** autenticadas por sessão, injeta o access token guardado na sessão
 * (renovando com o refresh token quando expira). Requisições Bearer passam intactas.
 * Roda depois do springSecurityFilterChain (registrado em SecurityConfig).
 */
public class SessionTokenRelayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenRelayFilter.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public SessionTokenRelayFilter(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2)) {
            chain.doFilter(request, response);
            return;
        }

        OAuth2AuthorizedClient client = authorize(oauth2, request, response);
        if (client == null) {
            // Refresh token expirado/revogado: a sessão não serve mais; o cliente deve refazer o login.
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(new BearerTokenRequestWrapper(request, client.getAccessToken().getTokenValue()), response);
    }

    private OAuth2AuthorizedClient authorize(OAuth2AuthenticationToken authentication,
                                             HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(authentication.getAuthorizedClientRegistrationId())
                .principal(authentication)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();
        try {
            return authorizedClientManager.authorize(authorizeRequest);
        } catch (OAuth2AuthorizationException ex) {
            log.warn("Falha ao renovar o token da sessão: {}", ex.getError().getErrorCode());
            return null;
        }
    }
}
