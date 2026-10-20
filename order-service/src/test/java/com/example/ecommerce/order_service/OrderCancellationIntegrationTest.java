package com.example.ecommerce.order_service;

import com.example.ecommerce.order_service.entities.Order;
import com.example.ecommerce.order_service.entities.OutboxEvent;
import com.example.ecommerce.order_service.enums.OrderStatus;
import com.example.ecommerce.order_service.repositories.OrderRepository;
import com.example.ecommerce.order_service.repositories.OutboxEventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "KC_ORDER_SERVICE_CLIENT_SECRET=order-secret-test",
        "DB_PASSWORD=unused",
        // Poller desligado na prática: o teste inspeciona o outbox antes da publicação.
        "app.outbox.fixed-delay=3600000"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderCancellationIntegrationTest {

    private static final String TOKEN_PATH = "/realms/ecommerce/protocol/openid-connect/token";
    private static final String RELEASE_PATH = "/api/inventory/release";
    private static final String ORDER_BODY = """
            {"items":[{"productId":"p1","quantity":2}]}
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

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void stubs() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(TOKEN_PATH).willReturn(okJson("""
                {"access_token":"svc-token","token_type":"Bearer","expires_in":300}
                """)));
        WIREMOCK.stubFor(WireMock.get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"Teclado","price":10.00}
                """)));
        WIREMOCK.stubFor(WireMock.post("/api/inventory/reserve").willReturn(aResponse().withStatus(204)));
        WIREMOCK.stubFor(WireMock.post(RELEASE_PATH).willReturn(aResponse().withStatus(204)));
    }

    @Test
    void ownerCancelsOrderReleasingStockOnceAndEnqueuingEvent() throws Exception {
        UUID orderId = createOrder("owner-sub");

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("owner-sub"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Comprei errado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Order cancelled = orderRepository.findById(orderId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo("owner-sub");
        assertThat(cancelled.getCancellationReason()).isEqualTo("Comprei errado");

        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(RELEASE_PATH))
                .withHeader("Authorization", equalTo("Bearer svc-token"))
                .withRequestBody(equalToJson("""
                        {"items":[{"skuCode":"p1","quantity":2}]}
                        """)));
        assertThat(outboxEventRepository.findAll())
                .filteredOn(e -> e.getAggregateId().equals(orderId))
                .extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder("ORDER_CREATED", "ORDER_CANCELLED");

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("owner-sub")))
                .andExpect(status().isOk());
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(RELEASE_PATH)));
    }

    @Test
    void releaseFailureRollsBackStatus() throws Exception {
        UUID orderId = createOrder("owner-sub");
        WIREMOCK.stubFor(WireMock.post(RELEASE_PATH).willReturn(aResponse().withStatus(503)));

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("owner-sub")))
                .andExpect(status().isBadGateway());

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getCancelledAt()).isNull();
        assertThat(outboxEventRepository.findAll())
                .filteredOn(e -> e.getAggregateId().equals(orderId))
                .extracting(OutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED");
    }

    @Test
    void anotherCustomerGetsNotFound() throws Exception {
        UUID orderId = createOrder("owner-sub");

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("intruder-sub")))
                .andExpect(status().isNotFound());

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        WIREMOCK.verify(0, postRequestedFor(urlEqualTo(RELEASE_PATH)));
    }

    @Test
    void completedOrderCannotBeCancelled() throws Exception {
        UUID orderId = createOrder("owner-sub");
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("owner-sub")))
                .andExpect(status().isConflict());
        WIREMOCK.verify(0, postRequestedFor(urlEqualTo(RELEASE_PATH)));
    }

    @Test
    void staleVersionIsRejected() {
        UUID orderId = createOrderUnchecked("owner-sub");
        Order first = orderRepository.findById(orderId).orElseThrow();
        Order stale = orderRepository.findById(orderId).orElseThrow();

        first.setStatus(OrderStatus.COMPLETED);
        orderRepository.saveAndFlush(first);

        stale.setStatus(OrderStatus.CANCELLED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderRepository.saveAndFlush(stale))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    private UUID createOrderUnchecked(String subject) {
        try {
            return createOrder(subject);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private UUID createOrder(String subject) throws Exception {
        String body = mvc.perform(post("/api/order").with(user(subject))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static RequestPostProcessor user(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
