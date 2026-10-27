package com.example.ecommerce.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import jakarta.servlet.http.Cookie;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionChainTest extends GatewayTestSupport {

    @MockitoBean
    private OAuth2AuthorizedClientManager authorizedClientManager;

    @Autowired
    private ClientRegistrationRepository clientRegistrations;

    @BeforeEach
    void authorizedClient() {
        var registration = clientRegistrations.findByRegistrationId("keycloak");
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "relay-token",
                Instant.now(), Instant.now().plusSeconds(300));
        when(authorizedClientManager.authorize(any())).thenReturn(new OAuth2AuthorizedClient(registration, "user-sub", token));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customer() {
        return oidcLogin()
                .clientRegistration(clientRegistrations.findByRegistrationId("keycloak"))
                .idToken(t -> t.subject("user-sub")
                        .claim("name", "Ana Cliente")
                        .claim("email", "ana@ecommerce.local")
                        .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor xsrf() throws Exception {
        Cookie cookie = mvc.perform(get("/bff/me").with(customer())).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-XSRF-TOKEN", cookie.getValue());
            return request;
        };
    }

    @Test
    void apiWithoutSessionIs401NotRedirect() throws Exception {
        mvc.perform(get("/api/order")).andExpect(status().isUnauthorized());
        mvc.perform(get("/bff/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void browserNavigationWithoutSessionStartsLogin() throws Exception {
        mvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("http://localhost:8180/realms/ecommerce/protocol/openid-connect/auth"),
                        org.hamcrest.Matchers.containsString("code_challenge_method=S256"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/product", "/api/product/123", "/api/order", "/api/order/123",
            "/api/inventory", "/api/inventory/123"})
    void sessionRequestIsRelayedWithoutBrowserCredentials(String path) throws Exception {
        mvc.perform(get(path).with(customer()).cookie(new Cookie("SESSION", "abc"))
                        .header("X-XSRF-TOKEN", "browser-csrf"))
                .andExpect(status().isOk());

        BACKEND.verify(getRequestedFor(urlEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer relay-token"))
                .withoutHeader("Cookie")
                .withoutHeader("X-XSRF-TOKEN"));
    }

    @Test
    void writeWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/order").with(customer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        BACKEND.verify(0, postRequestedFor(urlEqualTo("/api/order")));
    }

    @Test
    void writeWithCsrfIsRelayed() throws Exception {
        mvc.perform(post("/api/order").with(customer()).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        BACKEND.verify(postRequestedFor(urlEqualTo("/api/order"))
                .withHeader("Authorization", equalTo("Bearer relay-token"))
                .withoutHeader("X-XSRF-TOKEN"));
    }

    @Test
    void meReturnsUserAndSetsCsrfCookie() throws Exception {
        var result = mvc.perform(get("/bff/me").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("user-sub"))
                .andExpect(jsonPath("$.name").value("Ana Cliente"))
                .andExpect(jsonPath("$.email").value("ana@ecommerce.local"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andReturn();

        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN").isHttpOnly()).isFalse();
    }

    @Test
    void reservationsLedgerIsDeniedEvenWithSession() throws Exception {
        mvc.perform(post("/api/inventory/reservations/0199f5d2-0000-7000-8000-000000000001").with(customer()).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRedirectsToKeycloakEndSession() throws Exception {
        mvc.perform(post("/logout").with(customer()).with(xsrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("http://localhost:8180/realms/ecommerce/protocol/openid-connect/logout"),
                        org.hamcrest.Matchers.containsString("post_logout_redirect_uri=http://localhost:3000/"))));
    }
}
