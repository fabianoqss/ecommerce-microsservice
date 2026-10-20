package com.example.ecommerce.order_service;

import com.example.ecommerce.order_service.repositories.OrderRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Exige compensação da reserva quando o banco rejeita a criação do pedido.
 * Se o erro só ocorrer no commit, fora do try/catch de createOrder, a verificação
 * da chamada de release deve falhar. O teste não abre uma transação própria,
 * permitindo que a transação real do serviço termine durante a requisição.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "KC_ORDER_SERVICE_CLIENT_SECRET=order-secret-test",
        "DB_PASSWORD=unused",
        "app.outbox.fixed-delay=3600000"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderCreateCompensationIntegrationTest {

    private static final String TOKEN_PATH = "/realms/ecommerce/protocol/openid-connect/token";
    private static final String RESERVE_PATH = "/api/inventory/reserve";
    private static final String RELEASE_PATH = "/api/inventory/release";
    private static final String INVENTORY_BODY = """
            {"items":[{"skuCode":"p1","quantity":1}]}
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

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void stubs() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(TOKEN_PATH).willReturn(okJson("""
                {"access_token":"svc-token","token_type":"Bearer","expires_in":300}
                """)));
        // Excede o VARCHAR(255) de tb_order_items.product_name no Postgres real.
        WIREMOCK.stubFor(WireMock.get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"%s","price":10.00}
                """.formatted("x".repeat(300)))));
        WIREMOCK.stubFor(WireMock.post(RESERVE_PATH).willReturn(aResponse().withStatus(204)));
        WIREMOCK.stubFor(WireMock.post(RELEASE_PATH).willReturn(aResponse().withStatus(204)));
    }

    @Test
    void databaseFailureDuringOrderCreationReleasesReservedStockExactlyOnce() throws Exception {
        var response = mvc.perform(post("/api/order")
                        .with(jwt().jwt(j -> j.subject("owner-sub"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"p1","quantity":1}]}
                                """))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isNotEqualTo(HttpStatus.CREATED.value());
        assertThat(orderRepository.findAll()).isEmpty();

        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(RESERVE_PATH))
                .withRequestBody(equalToJson(INVENTORY_BODY)));
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(RELEASE_PATH)));
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(RELEASE_PATH))
                .withRequestBody(equalToJson(INVENTORY_BODY)));
    }

    @Test
    void failureOnOrderInsertAtCommitReleasesReservedStock() throws Exception {
        // Preço cabe em NUMERIC(19,2) do item, mas o total (1e15 * 1000 = 1e18) estoura o de
        // tb_orders.total_value. O INSERT do pedido (UUID gerado na aplicação) só roda no flush do commit.
        WIREMOCK.stubFor(WireMock.get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"Teclado","price":1000000000000000.00}
                """)));

        var response = mvc.perform(post("/api/order")
                        .with(jwt().jwt(j -> j.subject("owner-sub"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"p1","quantity":1000}]}
                                """))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isNotEqualTo(HttpStatus.CREATED.value());
        assertThat(orderRepository.findAll()).isEmpty();
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(RESERVE_PATH)));
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(RELEASE_PATH)));
    }
}
