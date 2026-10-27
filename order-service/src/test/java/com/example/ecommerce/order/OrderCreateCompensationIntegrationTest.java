package com.example.ecommerce.order;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.ReservationMode;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import com.example.ecommerce.order.service.OrderReconciler;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "KC_ORDER_SERVICE_CLIENT_SECRET=order-secret-test",
        "DB_PASSWORD=unused",
        "app.outbox.fixed-delay=3600000",
        "ecommerce.order.reconciliation.fixed-delay=3600000",
        "ecommerce.order.reconciliation.min-age=2m",
        "ecommerce.order.reservation.retry-delay=10ms",
        "spring.cloud.openfeign.client.config.default.read-timeout=300"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderCreateCompensationIntegrationTest {

    private static final String TOKEN_PATH = "/realms/ecommerce/protocol/openid-connect/token";
    private static final String RESERVATION_URL_PATTERN = "/api/inventory/reservations/.*";
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
        registry.add("clients.inventory.url", WIREMOCK::baseUrl);
        registry.add("clients.product.url", WIREMOCK::baseUrl);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderReconciler orderReconciler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicBoolean triggerReconcilerOnPut = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        triggerReconcilerOnPut.set(false);
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(TOKEN_PATH).willReturn(okJson("""
                {"access_token":"svc-token","token_type":"Bearer","expires_in":300}
                """)));
        WIREMOCK.stubFor(WireMock.get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"Teclado","price":10.00}
                """)));
    }

    @Test
    void put201ConfirmsOrderInLedgerModeAndEnqueuesOutboxEvent() throws Exception {
        WIREMOCK.stubFor(WireMock.put(urlPathMatching(RESERVATION_URL_PATTERN))
                .willReturn(aResponse().withStatus(201)));

        String body = mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("owner-sub"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"p1","quantity":1}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();

        UUID orderId = UUID.fromString(JsonPath.read(body, "$.id"));
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReservationMode()).isEqualTo(ReservationMode.LEDGER);
        assertThat(order.getConfirmedAt()).isNotNull();

        WIREMOCK.verify(1, putRequestedFor(urlEqualTo("/api/inventory/reservations/" + orderId))
                .withRequestBody(equalToJson(INVENTORY_BODY)));
        assertThat(outboxEventRepository.findAll())
                .filteredOn(e -> e.getAggregateId().equals(orderId))
                .extracting(OutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED");
    }

    @Test
    void put409CancelsOrderImmediatelyReturns409AndHidesFromCustomerList() throws Exception {
        WIREMOCK.stubFor(WireMock.put(urlPathMatching(RESERVATION_URL_PATTERN))
                .willReturn(aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":409,\"detail\":\"Sem estoque\"}")));

        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("owner-sub"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"p1","quantity":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).singleElement().satisfies(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getConfirmedAt()).isNull();
            assertThat(order.getCancellationReason()).isEqualTo("Estoque insuficiente");
        });
        UUID orderId = orders.getFirst().getId();

        WIREMOCK.verify(1, putRequestedFor(urlEqualTo("/api/inventory/reservations/" + orderId)));
        assertThat(outboxEventRepository.findAll()).isEmpty();

        mvc.perform(get("/api/order").with(user("owner-sub")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/order/{id}", orderId).with(user("owner-sub")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void putTimeoutLeavesOrderPendingAndReconcilerExpiresDeletesReservationAndCancels() throws Exception {
        WIREMOCK.stubFor(WireMock.put(urlPathMatching(RESERVATION_URL_PATTERN))
                .willReturn(aResponse().withFixedDelay(600).withStatus(201)));
        WIREMOCK.stubFor(WireMock.delete(urlPathMatching(RESERVATION_URL_PATTERN))
                .willReturn(aResponse().withStatus(204)));

        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("owner-sub"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"p1","quantity":1}]}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));

        Order pending = orderRepository.findAll().getFirst();
        UUID orderId = pending.getId();
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(pending.getConfirmedAt()).isNull();
        WIREMOCK.verify(3, putRequestedFor(urlEqualTo("/api/inventory/reservations/" + orderId)));

        jdbcTemplate.update("UPDATE tb_orders SET order_time = order_time - INTERVAL '10 minutes' WHERE id = ?", orderId);
        WIREMOCK.stubFor(WireMock.delete(urlEqualTo("/api/inventory/reservations/" + orderId))
                .inScenario("reconcile-delete")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-attempt"));
        WIREMOCK.stubFor(WireMock.delete(urlEqualTo("/api/inventory/reservations/" + orderId))
                .inScenario("reconcile-delete")
                .whenScenarioStateIs("second-attempt")
                .willReturn(aResponse().withStatus(204)));

        orderReconciler.reconcile();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);

        orderReconciler.reconcile();
        Order reconciled = orderRepository.findById(orderId).orElseThrow();
        assertThat(reconciled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reconciled.getConfirmedAt()).isNull();
        WIREMOCK.verify(2, deleteRequestedFor(urlEqualTo("/api/inventory/reservations/" + orderId)));
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void raceBetweenConfirmationAndReconcilerReturns503AndNeverConfirmsExpiredOrder() throws Exception {
        WIREMOCK.stubFor(WireMock.delete(urlPathMatching(RESERVATION_URL_PATTERN))
                .willReturn(aResponse().withStatus(204)));
        WIREMOCK.stubFor(WireMock.put(urlPathMatching(RESERVATION_URL_PATTERN))
                .willReturn(aResponse().withStatus(201)));

        triggerReconcilerOnPut.set(true);
        WIREMOCK.addMockServiceRequestListener((request, response) -> {
            if (triggerReconcilerOnPut.get()
                    && request.getMethod().isOneOf(com.github.tomakehurst.wiremock.http.RequestMethod.PUT)
                    && request.getUrl().startsWith("/api/inventory/reservations/")
                    && triggerReconcilerOnPut.compareAndSet(true, false)) {
                jdbcTemplate.update("UPDATE tb_orders SET order_time = order_time - INTERVAL '10 minutes'");
                orderReconciler.reconcile();
            }
        });

        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("owner-sub"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"p1","quantity":1}]}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));

        Order order = orderRepository.findAll().getFirst();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void databaseFailureWhenPersistingPendingOrderNeverCallsInventory() throws Exception {
        WIREMOCK.stubFor(WireMock.get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"%s","price":10.00}
                """.formatted("x".repeat(300)))));

        var response = mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("owner-sub"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"p1","quantity":1}]}
                                """))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(orderRepository.findAll()).isEmpty();
        WIREMOCK.verify(0, putRequestedFor(urlPathMatching(RESERVATION_URL_PATTERN)));
        WIREMOCK.verify(0, deleteRequestedFor(urlPathMatching(RESERVATION_URL_PATTERN)));
    }

    private static RequestPostProcessor user(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
