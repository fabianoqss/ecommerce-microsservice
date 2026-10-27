package com.example.ecommerce.order;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.entity.OrderItem;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.ReservationMode;
import com.example.ecommerce.order.dto.request.InventoryItemDTO;
import com.example.ecommerce.order.infra.messaging.OrderEventDTO;
import com.example.ecommerce.order.infra.messaging.OutboxDispatcher;
import com.example.ecommerce.order.infra.messaging.RabbitMQConfig;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.ParameterizedTypeReference;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
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
        "ecommerce.order.reconciliation.fixed-delay=3600000"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderCancellationIntegrationTest {

    private static final String TOKEN_PATH = "/realms/ecommerce/protocol/openid-connect/token";
    private static final String ORDER_BODY = """
            {"items":[{"productId":"p1","quantity":2}]}
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.12");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.5-management");

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
    private OutboxDispatcher outboxDispatcher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void stubs() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(TOKEN_PATH).willReturn(okJson("""
                {"access_token":"svc-token","token_type":"Bearer","expires_in":300}
                """)));
        WIREMOCK.stubFor(WireMock.get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"Teclado","price":10.00}
                """)));
        WIREMOCK.stubFor(WireMock.put(urlPathMatching("/api/inventory/reservations/.*"))
                .willReturn(aResponse().withStatus(201)));
    }

    @Test
    void ownerCancelsOrderEnqueuingV3EventWithoutCallingInventoryHttp() throws Exception {
        UUID orderId = createOrder("owner-sub");
        WIREMOCK.resetRequests();

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("owner-sub"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Comprei errado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Order cancelled = orderRepository.findById(orderId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo("owner-sub");
        assertThat(cancelled.getCancellationReason()).isEqualTo("Comprei errado");
        assertThat(cancelled.getCancelledAt()).isNotNull();

        WIREMOCK.verify(0, anyRequestedFor(urlMatching("/api/inventory/.*")));

        List<OutboxEvent> orderEvents = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId))
                .toList();
        assertThat(orderEvents).extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder("ORDER_CREATED", "ORDER_CANCELLED");

        OutboxEvent cancelledOutbox = orderEvents.stream()
                .filter(e -> "ORDER_CANCELLED".equals(e.getEventType()))
                .findFirst()
                .orElseThrow();

        String payload = cancelledOutbox.getPayload();
        assertThat((Integer) JsonPath.read(payload, "$.version")).isEqualTo(3);
        assertThat((String) JsonPath.read(payload, "$.orderId")).isEqualTo(orderId.toString());
        assertThat((String) JsonPath.read(payload, "$.status")).isEqualTo("CANCELLED");
        assertThat((String) JsonPath.read(payload, "$.reservationMode")).isEqualTo("LEDGER");
        assertThat((String) JsonPath.read(payload, "$.items[0].skuCode")).isEqualTo("p1");
        assertThat((Integer) JsonPath.read(payload, "$.items[0].quantity")).isEqualTo(2);

        outboxDispatcher.dispatch(cancelledOutbox.getId());
        OrderEventDTO inventoryMsg = rabbitTemplate.receiveAndConvert(
                RabbitMQConfig.INVENTORY_CANCELLED_QUEUE,
                ParameterizedTypeReference.forType(OrderEventDTO.class));
        assertThat(inventoryMsg).isNotNull();
        assertThat(inventoryMsg.eventId()).isEqualTo(cancelledOutbox.getEventId());
        assertThat(inventoryMsg.version()).isEqualTo(3);
        assertThat(inventoryMsg.orderId()).isEqualTo(orderId);
        assertThat(inventoryMsg.reservationMode()).isEqualTo(ReservationMode.LEDGER);
        assertThat(inventoryMsg.items()).containsExactly(new InventoryItemDTO("p1", 2));

        OrderEventDTO notificationMsg = rabbitTemplate.receiveAndConvert(
                RabbitMQConfig.CANCELLED_QUEUE,
                ParameterizedTypeReference.forType(OrderEventDTO.class));
        assertThat(notificationMsg).isNotNull();
        assertThat(notificationMsg.eventId()).isEqualTo(cancelledOutbox.getEventId());

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("owner-sub")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(outboxEventRepository.findAll())
                .filteredOn(e -> e.getAggregateId().equals(orderId) && "ORDER_CANCELLED".equals(e.getEventType()))
                .hasSize(1);
    }

    @Test
    void cancellingLegacyOrderPublishesV3WithLegacyModeAndAggregatedItems() throws Exception {
        OrderItem item1 = new OrderItem();
        item1.setProductId("p2");
        item1.setProductName("Mouse");
        item1.setQuantity(1);
        item1.setPrice(new BigDecimal("15.00"));

        OrderItem item2 = new OrderItem();
        item2.setProductId("p1");
        item2.setProductName("Teclado");
        item2.setQuantity(2);
        item2.setPrice(new BigDecimal("10.00"));

        OrderItem item3 = new OrderItem();
        item3.setProductId("p2");
        item3.setProductName("Mouse");
        item3.setQuantity(3);
        item3.setPrice(new BigDecimal("15.00"));

        Order legacyOrder = new Order();
        legacyOrder.setUserID("legacy-owner");
        legacyOrder.setOrderTime(LocalDateTime.now().minusDays(1));
        legacyOrder.setStatus(OrderStatus.CONFIRMED);
        legacyOrder.setConfirmedAt(Instant.now().minusSeconds(3600));
        legacyOrder.setReservationMode(ReservationMode.LEGACY);
        legacyOrder.setTotalValue(new BigDecimal("80.00"));
        legacyOrder.setItems(List.of(item1, item2, item3));
        legacyOrder = orderRepository.saveAndFlush(legacyOrder);
        UUID orderId = legacyOrder.getId();

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("legacy-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        WIREMOCK.verify(0, anyRequestedFor(urlMatching("/api/inventory/.*")));

        OutboxEvent cancelledOutbox = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId) && "ORDER_CANCELLED".equals(e.getEventType()))
                .findFirst()
                .orElseThrow();

        String payload = cancelledOutbox.getPayload();
        assertThat((Integer) JsonPath.read(payload, "$.version")).isEqualTo(3);
        assertThat((String) JsonPath.read(payload, "$.reservationMode")).isEqualTo("LEGACY");
        assertThat((String) JsonPath.read(payload, "$.items[0].skuCode")).isEqualTo("p1");
        assertThat((Integer) JsonPath.read(payload, "$.items[0].quantity")).isEqualTo(2);
        assertThat((String) JsonPath.read(payload, "$.items[1].skuCode")).isEqualTo("p2");
        assertThat((Integer) JsonPath.read(payload, "$.items[1].quantity")).isEqualTo(4);
    }

    @Test
    void readEndpointsMapLazyItemsWithoutOpenInView() throws Exception {
        UUID orderId = createOrder("reader-sub");

        mvc.perform(get("/api/order/{id}", orderId).with(user("reader-sub")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value("p1"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        mvc.perform(get("/api/order").with(user("reader-sub")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(orderId.toString()))
                .andExpect(jsonPath("$.content[0].items[0].productId").value("p1"));
    }

    @Test
    void anotherCustomerGetsNotFound() throws Exception {
        UUID orderId = createOrder("owner-sub");
        WIREMOCK.resetRequests();

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("intruder-sub")))
                .andExpect(status().isNotFound());

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        WIREMOCK.verify(0, anyRequestedFor(urlMatching("/api/inventory/.*")));
    }

    @Test
    void completedOrderCannotBeCancelled() throws Exception {
        UUID orderId = createOrder("owner-sub");
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);
        WIREMOCK.resetRequests();

        mvc.perform(post("/api/order/{id}/cancel", orderId).with(user("owner-sub")))
                .andExpect(status().isConflict());
        WIREMOCK.verify(0, anyRequestedFor(urlMatching("/api/inventory/.*")));
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
