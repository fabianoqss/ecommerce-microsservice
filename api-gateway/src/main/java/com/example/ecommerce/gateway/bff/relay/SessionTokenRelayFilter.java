package com.example.ecommerce.gateway.bff.relay;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.web.client.RestClientException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

public class SessionTokenRelayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenRelayFilter.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final JsonMapper jsonMapper;
    private final Counter sessionExpiredFailures;
    private final Counter identityProviderUnavailableFailures;

    public SessionTokenRelayFilter(OAuth2AuthorizedClientManager authorizedClientManager,
                                   JsonMapper jsonMapper, MeterRegistry meterRegistry) {
        this.authorizedClientManager = authorizedClientManager;
        this.jsonMapper = jsonMapper;
        this.sessionExpiredFailures = meterRegistry.counter("gateway.token.relay.failures", "result", "session_expired");
        this.identityProviderUnavailableFailures = meterRegistry.counter("gateway.token.relay.failures", "result", "idp_unavailable");
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

        OAuth2AuthorizedClient client;
        try {
            client = authorize(oauth2, request, response);
        } catch (OAuth2AuthorizationException ex) {
            String errorCode = ex.getError().getErrorCode();
            if (OAuth2ErrorCodes.INVALID_GRANT.equals(errorCode) || OAuth2ErrorCodes.INVALID_TOKEN.equals(errorCode)) {
                log.info("Sessão expirada ao renovar o token: {}", errorCode);
                expireSession(request, response);
            } else {
                log.warn("Serviço de identidade indisponível ao renovar o token: {}", errorCode);
                identityProviderUnavailable(response);
            }
            return;
        } catch (RestClientException ex) {
            log.warn("Serviço de identidade indisponível ao renovar o token: {}", ex.getClass().getSimpleName());
            identityProviderUnavailable(response);
            return;
        }
        if (client == null) {
            log.info("Sessão sem cliente OAuth2 autorizado");
            expireSession(request, response);
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
        return authorizedClientManager.authorize(authorizeRequest);
    }

    private void expireSession(HttpServletRequest request, HttpServletResponse response) throws IOException {
        sessionExpiredFailures.increment();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private void identityProviderUnavailable(HttpServletResponse response) throws IOException {
        identityProviderUnavailableFailures.increment();
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Serviço de identidade temporariamente indisponível; tente novamente em instantes");
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
