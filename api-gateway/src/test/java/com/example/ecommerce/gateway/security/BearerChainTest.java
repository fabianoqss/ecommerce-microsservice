package com.example.ecommerce.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BearerChainTest extends GatewayTestSupport {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("customer-token")).thenReturn(Jwt.withTokenValue("customer-token")
                .header("alg", "RS256").subject("user-sub")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .build());
        when(jwtDecoder.decode("garbage")).thenThrow(new BadJwtException("invalid"));
    }

    @Test
    void validBearerIsForwardedUnchangedWithoutCreatingSession() throws Exception {
        MvcResult result = mvc.perform(get("/api/order").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isOk())
                .andReturn();

        BACKEND.verify(getRequestedFor(urlEqualTo("/api/order"))
                .withHeader("Authorization", equalTo("Bearer customer-token")));
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION"));
    }

    @Test
    void invalidBearerIs401AndNotForwarded() throws Exception {
        mvc.perform(get("/api/order").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());

        BACKEND.verify(0, getRequestedFor(urlEqualTo("/api/order")));
    }

    @Test
    void reserveAndReleaseAreDeniedAtTheEdge() throws Exception {
        mvc.perform(post("/api/inventory/reserve").header("Authorization", "Bearer customer-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/release").header("Authorization", "Bearer customer-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        BACKEND.verify(0, postRequestedFor(urlMatching("/api/inventory/.*")));
    }

    @Test
    void bffEndpointsAreNotAvailableToBearerClients() throws Exception {
        mvc.perform(get("/bff/me").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCanReadCatalog() throws Exception {
        mvc.perform(get("/api/product")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/product", "/api/product/sku-1", "/api/order", "/api/order/123", "/api/inventory", "/api/inventory/sku-1"})
    void rootAndChildRoutesRemoveBrowserCredentials(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer customer-token")
                        .cookie(new Cookie("SESSION", "browser-session"))
                        .header("X-XSRF-TOKEN", "browser-csrf"))
                .andExpect(status().isOk());

        BACKEND.verify(getRequestedFor(urlEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer customer-token"))
                .withoutHeader("Cookie")
                .withoutHeader("X-XSRF-TOKEN"));
    }

    @Test
    void gatewayActuatorEndpointIsNotExposed() throws Exception {
        mvc.perform(get("/actuator/gateway/routes").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
    }
}
