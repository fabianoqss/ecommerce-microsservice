package com.example.ecommerce.gateway.bff.relay;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SessionTokenRelayFilterTest {

    private final OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    private final SessionTokenRelayFilter filter = new SessionTokenRelayFilter(manager);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
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
        loginWithSession();
        when(manager.authorize(any())).thenReturn(null);
        var session = new MockHttpSession();
        var request = new MockHttpServletRequest("GET", "/api/order");
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(chain);
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
