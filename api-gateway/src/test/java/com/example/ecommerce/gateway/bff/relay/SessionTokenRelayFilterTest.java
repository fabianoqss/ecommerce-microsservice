package com.example.ecommerce.gateway.bff.relay;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

class SessionTokenRelayFilterTest {

    private final OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SessionTokenRelayFilter filter = new SessionTokenRelayFilter(manager, jsonMapper, meterRegistry);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        meterRegistry.close();
    }

    @Test
    void relayUsesAuthorizedClientFromSession() throws Exception {
        loginWithSession();
        when(manager.authorize(any())).thenReturn(authorizedClient("relay-token"));
        var request = new MockHttpServletRequest("GET", "/api/order");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        var forwarded = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(forwarded.capture(), any());
        assertThat(forwarded.getValue().getHeader("Authorization")).isEqualTo("Bearer relay-token");
        assertThat(java.util.Collections.list(forwarded.getValue().getHeaders("Authorization")))
                .containsExactly("Bearer relay-token");
    }

    @Test
    void relayWithoutAuthorizedClientReturns401AndInvalidatesSession() throws Exception {
        when(manager.authorize(any())).thenReturn(null);
        var session = authenticatedSession();
        var request = new MockHttpServletRequest("GET", "/api/order");
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(meterRegistry.get("gateway.token.relay.failures").tag("result", "session_expired")
                .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("gateway.token.relay.failures").tag("result", "idp_unavailable")
                .counter().count()).isZero();
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_grant", "invalid_token"})
    void unrecoverableAuthorizationFailureReturns401AndInvalidatesSession(String errorCode) throws Exception {
        when(manager.authorize(any())).thenThrow(new OAuth2AuthorizationException(new OAuth2Error(errorCode)));
        var session = authenticatedSession();
        var request = new MockHttpServletRequest("GET", "/api/order");
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(meterRegistry.get("gateway.token.relay.failures").tag("result", "session_expired")
                .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("gateway.token.relay.failures").tag("result", "idp_unavailable")
                .counter().count()).isZero();
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_token_response", "server_error", "temporarily_unavailable", "invalid_client",
            "unexpected_error"})
    void transientAuthorizationFailureReturns503AndPreservesSession(String errorCode) throws Exception {
        when(manager.authorize(any())).thenThrow(new OAuth2AuthorizationException(new OAuth2Error(errorCode)));

        assertIdentityProviderUnavailable();
    }

    @ParameterizedTest
    @MethodSource("restClientFailures")
    void restClientFailureReturns503AndPreservesSession(RestClientException failure) throws Exception {
        when(manager.authorize(any())).thenThrow(failure);

        assertIdentityProviderUnavailable();
    }

    @Test
    void bearerRequestsPassThroughUntouched() throws Exception {
        Jwt jwt = Jwt.withTokenValue("api-token").header("alg", "RS256").subject("s").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var request = new MockHttpServletRequest("GET", "/api/order");
        request.addHeader("Authorization", "Bearer api-token");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(eq(request), any());
        verifyNoInteractions(manager);
    }

    @Test
    void nonApiPathsAreNotRelayed() throws Exception {
        loginWithSession();
        var request = new MockHttpServletRequest("GET", "/bff/me");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(eq(request), any());
        verifyNoInteractions(manager);
    }

    private void assertIdentityProviderUnavailable() throws Exception {
        var session = authenticatedSession();
        var context = SecurityContextHolder.getContext();
        var authentication = context.getAuthentication();
        var request = new MockHttpServletRequest("GET", "/api/order");
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        var problem = jsonMapper.readTree(response.getContentAsByteArray());
        assertThat(problem.get("title").asText()).isEqualTo("Service Unavailable");
        assertThat(problem.get("status").asInt()).isEqualTo(503);
        assertThat(problem.get("detail").asText())
                .isEqualTo("Serviço de identidade temporariamente indisponível; tente novamente em instantes");
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(SPRING_SECURITY_CONTEXT_KEY)).isSameAs(context);
        assertThat(context.getAuthentication()).isSameAs(authentication);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        assertThat(meterRegistry.get("gateway.token.relay.failures").tag("result", "idp_unavailable")
                .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("gateway.token.relay.failures").tag("result", "session_expired")
                .counter().count()).isZero();
        verifyNoInteractions(chain);
    }

    private static Stream<RestClientException> restClientFailures() {
        return Stream.of(new RestClientException("Token endpoint failure"),
                new ResourceAccessException("Token endpoint unavailable", new IOException("Connection refused")));
    }

    private static MockHttpSession authenticatedSession() {
        loginWithSession();
        var session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());
        return session;
    }

    private static void loginWithSession() {
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OIDC_USER")), Map.of("sub", "user-sub"), "sub");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
    }

    private static OAuth2AuthorizedClient authorizedClient(String token) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("keycloak")
                .clientId("ecommerce-bff")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://kc/auth").tokenUri("http://kc/token")
                .build();
        var accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                Instant.now(), Instant.now().plusSeconds(300));
        return new OAuth2AuthorizedClient(registration, "user-sub", accessToken);
    }
}
