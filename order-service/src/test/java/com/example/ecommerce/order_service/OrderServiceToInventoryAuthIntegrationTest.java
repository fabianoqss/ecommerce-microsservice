package com.example.ecommerce.order_service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sobe o order-service com Postgres e RabbitMQ reais; Keycloak, product e inventory são simulados
 * pelo WireMock. Prova que a chamada ao inventory leva o token obtido via client_credentials.
 * O contexto é recriado a cada teste porque o token de serviço fica em cache no
 * OAuth2AuthorizedClientService e mascararia o cenário de Keycloak fora do ar.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "KC_ORDER_SERVICE_CLIENT_SECRET=order-secret-test",
        "DB_PASSWORD=unused"
})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderServiceToInventoryAuthIntegrationTest {

    private static final String TOKEN_PATH = "/realms/ecommerce/protocol/openid-connect/token";
    private static final String ORDER_BODY = """
            {"items":[{"productId":"p1","quantity":1}]}
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

    static final WireMockServer WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("OIDC_TOKEN_URI", () -> WIREMOCK.baseUrl() + TOKEN_PATH);
        registry.add("spring.cloud.openfeign.client.config.inventory-service.url", WIREMOCK::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.product-service.url", WIREMOCK::baseUrl);
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void stubs() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"Teclado","price":10.00}
                """)));
        WIREMOCK.stubFor(WireMock.post("/api/inventory/reserve").willReturn(aResponse().withStatus(204)));
    }

    @Test
    void reserveCallCarriesClientCredentialsToken() throws Exception {
        WIREMOCK.stubFor(WireMock.post(TOKEN_PATH).willReturn(okJson("""
                {"access_token":"svc-token","token_type":"Bearer","expires_in":300}
                """)));

        mvc.perform(post("/api/order")
                        .with(jwt().jwt(j -> j.subject("user-sub")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isCreated());

        WIREMOCK.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=client_credentials")));
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/api/inventory/reserve"))
                .withHeader("Authorization", equalTo("Bearer svc-token")));
        WIREMOCK.verify(getRequestedFor(urlEqualTo("/api/product/p1"))
                .withoutHeader("Authorization"));
    }

    @Test
    void keycloakDownReturns503() throws Exception {
        WIREMOCK.stubFor(WireMock.post(TOKEN_PATH).willReturn(serverError()));

        mvc.perform(post("/api/order")
                        .with(jwt().jwt(j -> j.subject("user-sub")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isServiceUnavailable());

        WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/api/inventory/reserve")));
        WIREMOCK.verify(getRequestedFor(urlEqualTo("/api/product/p1"))
                .withoutHeader("Authorization"));
    }
}
