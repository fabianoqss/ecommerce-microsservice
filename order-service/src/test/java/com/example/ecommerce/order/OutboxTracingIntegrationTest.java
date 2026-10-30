package com.example.ecommerce.order;

import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OutboxStatus;
import com.example.ecommerce.order.dto.event.OrderEventDTO;
import com.example.ecommerce.order.infra.messaging.OutboxPoller;
import com.example.ecommerce.order.infra.messaging.RabbitMQConfig;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "KC_ORDER_SERVICE_CLIENT_SECRET=order-secret-test",
        "DB_PASSWORD=unused",
        "app.outbox.fixed-delay=3600000",
        "ecommerce.order.reconciliation.fixed-delay=3600000",
        "spring.test.tracing.export=true",
        "management.tracing.export.zipkin.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class OutboxTracingIntegrationTest {

    private static final String TRACE_ID = "463ac35c9f6413ad48485a3953bb6124";
    private static final String PARENT_ID = "a2fb4a1d1a96d312";
    private static final String TOKEN_PATH = "/realms/ecommerce/protocol/openid-connect/token";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.12");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.5-management");

    static final WireMockServer BACKEND = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        BACKEND.start();
    }

    private final MockMvc mvc;

    private final OutboxEventRepository repository;

    private final OutboxPoller poller;

    private final RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper;

    @Autowired
    OutboxTracingIntegrationTest(MockMvc mvc, OutboxEventRepository repository, OutboxPoller poller,
                                 RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.repository = repository;
        this.poller = poller;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("OIDC_TOKEN_URI", () -> BACKEND.baseUrl() + TOKEN_PATH);
        registry.add("clients.inventory.url", BACKEND::baseUrl);
        registry.add("clients.product.url", BACKEND::baseUrl);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @BeforeEach
    void prepare() {
        repository.deleteAll();
        var admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
        admin.purgeQueue(RabbitMQConfig.QUEUE, false);
        admin.purgeQueue(RabbitMQConfig.CANCELLED_QUEUE, false);
        admin.purgeQueue(RabbitMQConfig.INVENTORY_CANCELLED_QUEUE, false);
        BACKEND.resetAll();
        BACKEND.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(TOKEN_PATH).willReturn(okJson("""
                {"access_token":"svc-token","token_type":"Bearer","expires_in":300}
                """)));
        BACKEND.stubFor(get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"Teclado","price":10.00}
                """)));
        BACKEND.stubFor(put(urlPathMatching("/api/inventory/reservations/.*"))
                .willReturn(aResponse().withStatus(201)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00", "01"})
    void checkoutContinuesThroughFeignPersistenceAndRabbit(String flags) throws Exception {
        checkout(flags);

        OutboxEvent stored = repository.findAll().getFirst();
        assertThat(stored.getTraceparent()).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-" + flags);
        String clientHeader = "00-" + TRACE_ID + "-[0-9a-f]{16}-" + flags;
        BACKEND.verify(getRequestedFor(urlEqualTo("/api/product/p1"))
                .withHeader("traceparent", matching(clientHeader)));
        BACKEND.verify(putRequestedFor(urlPathMatching("/api/inventory/reservations/.*"))
                .withHeader("traceparent", matching(clientHeader)));

        dispatchOnPollerThread();

        assertPublished(RabbitMQConfig.QUEUE, flags);
        assertThat(repository.findById(stored.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void cancelledEventPropagatesToNotificationAndInventoryQueues() throws Exception {
        checkout("01");
        OutboxEvent created = repository.findAll().getFirst();
        mvc.perform(post("/api/order/{id}/cancel", created.getAggregateId())
                        .with(customer())
                        .header("traceparent", "00-" + TRACE_ID + "-" + PARENT_ID + "-01")
                        .contentType("application/json").content("{\"reason\":\"Changed mind\"}"))
                .andExpect(status().isOk());

        dispatchOnPollerThread();

        assertPublished(RabbitMQConfig.CANCELLED_QUEUE, "01");
        assertPublished(RabbitMQConfig.INVENTORY_CANCELLED_QUEUE, "01");
        assertThat(repository.findAll()).allSatisfy(event -> {
            assertThat(event.getTraceparent()).contains(TRACE_ID);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        });
    }

    @Test
    void persistedLegacyEventWithoutContextStillDispatches() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var payload = new OrderEventDTO(eventId, 2, orderId, "customer", "CONFIRMED",
                BigDecimal.TEN, LocalDateTime.now(), null, null);
        OutboxEvent stored = repository.saveAndFlush(OutboxEvent.pending(eventId, orderId,
                "ORDER_CREATED", objectMapper.writeValueAsString(payload)));

        dispatchOnPollerThread();

        Message message = rabbitTemplate.receive(RabbitMQConfig.QUEUE, 5000);
        assertThat(message).isNotNull();
        assertThat(objectMapper.readValue(message.getBody(), OrderEventDTO.class).eventId()).isEqualTo(eventId);
        OutboxEvent sent = repository.findById(stored.getId()).orElseThrow();
        assertThat(sent.getTraceparent()).isNull();
        assertThat(sent.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    private void checkout(String flags) throws Exception {
        mvc.perform(post("/api/order")
                        .with(customer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("traceparent", "00-" + TRACE_ID + "-" + PARENT_ID + "-" + flags)
                        .contentType("application/json")
                        .content("{\"items\":[{\"productId\":\"p1\",\"quantity\":2}]}"))
                .andExpect(status().isCreated());
    }

    private void dispatchOnPollerThread() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "outbox-poller-test"))) {
            executor.submit(poller::publishPendingEvents).get(20, TimeUnit.SECONDS);
        }
    }

    private void assertPublished(String queue, String flags) {
        Message message = rabbitTemplate.receive(queue, 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getHeaders().get("traceparent"))
                .asString().matches("00-" + TRACE_ID + "-[0-9a-f]{16}-" + flags);
    }

    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject("customer")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
