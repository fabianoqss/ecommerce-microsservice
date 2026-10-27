package com.example.ecommerce.order;

import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.dto.request.OrderItemRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.dto.response.CreateOrderResult;
import com.example.ecommerce.order.exception.IdempotencyKeyReuseException;
import com.example.ecommerce.order.exception.OrderInProgressException;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import com.example.ecommerce.order.service.OrderService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class OrderCheckoutIdempotencyIntegrationTest {

    private static final String TOKEN_PATH = "/realms/ecommerce/protocol/openid-connect/token";
    private static final String RESERVATION_URL_PATTERN = "/api/inventory/reservations/.*";
    private static final String USER_ID = "owner-sub";
    private static final OrderRequestDTO ORDER = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 1)));
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
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(TOKEN_PATH).willReturn(okJson("""
                {"access_token":"svc-token","token_type":"Bearer","expires_in":300}
                """)));
        WIREMOCK.stubFor(WireMock.get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"Teclado","price":10.00}
                """)));
        WIREMOCK.stubFor(WireMock.put(urlPathMatching(RESERVATION_URL_PATTERN))
                .willReturn(aResponse().withStatus(201)));
    }

    @Test
    void concurrentCheckoutsWithSameKeyCreateOneOrderAndReserveInventoryOnce() throws Exception {
        UUID key = UUID.randomUUID();
        WIREMOCK.stubFor(WireMock.put(urlPathMatching(RESERVATION_URL_PATTERN))
                .willReturn(aResponse().withFixedDelay(150).withStatus(201)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<CreateOrderResult> checkout = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new TimeoutException("Concurrent checkout did not start");
            }
            return orderService.createOrder(ORDER, USER_ID, key);
        };

        try {
            var first = executor.submit(checkout);
            var second = executor.submit(checkout);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<CreateOrderResult> successful = new ArrayList<>();
            List<UUID> inProgressIds = new ArrayList<>();
            for (var future : List.of(first, second)) {
                try {
                    successful.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOfSatisfying(OrderInProgressException.class,
                            inProgress -> inProgressIds.add(inProgress.getOrderId()));
                }
            }

            assertThat(successful).hasSizeBetween(1, 2);
            assertThat(successful.size() + inProgressIds.size()).isEqualTo(2);
            assertThat(successful).filteredOn(result -> !result.replayed()).hasSize(1);
            UUID orderId = successful.getFirst().orderResponseDTO().id();
            assertThat(successful).allSatisfy(result -> {
                assertThat(result.orderResponseDTO().id()).isEqualTo(orderId);
                assertThat(result.orderResponseDTO().status()).isEqualTo("CONFIRMED");
            });
            assertThat(inProgressIds).allSatisfy(id -> assertThat(id).isEqualTo(orderId));
            assertSingleConfirmedOrderAndReservation(key, orderId);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void repeatedCheckoutAfterConfirmationReplaysSameOrderWithoutAnotherReservation() {
        UUID key = UUID.randomUUID();

        var first = orderService.createOrder(ORDER, USER_ID, key);
        var replay = orderService.createOrder(ORDER, USER_ID, key);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderResponseDTO().id()).isEqualTo(first.orderResponseDTO().id());
        assertThat(replay.orderResponseDTO().status()).isEqualTo("CONFIRMED");
        assertThat(replay.orderResponseDTO().totalValue())
                .isEqualByComparingTo(first.orderResponseDTO().totalValue());
        assertThat(replay.orderResponseDTO().items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo("p1");
            assertThat(item.quantity()).isEqualTo(1);
        });
        assertSingleConfirmedOrderAndReservation(key, first.orderResponseDTO().id());
        WIREMOCK.verify(1, getRequestedFor(urlEqualTo("/api/product/p1")));
    }

    @Test
    void sameKeyWithDifferentCartRejectsCheckoutWithoutAnotherOrderOrReservation() {
        UUID key = UUID.randomUUID();
        var first = orderService.createOrder(ORDER, USER_ID, key);
        var differentCart = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 2)));

        assertThatThrownBy(() -> orderService.createOrder(differentCart, USER_ID, key))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        assertSingleConfirmedOrderAndReservation(key, first.orderResponseDTO().id());
        WIREMOCK.verify(1, getRequestedFor(urlEqualTo("/api/product/p1")));
    }

    private void assertSingleConfirmedOrderAndReservation(UUID key, UUID orderId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_orders WHERE userid = ? AND idempotency_key = ?",
                Long.class, USER_ID, key)).isEqualTo(1L);
        assertThat(orderRepository.findAll()).singleElement().satisfies(order -> {
            assertThat(order.getId()).isEqualTo(orderId);
            assertThat(order.getIdempotencyKey()).isEqualTo(key);
            assertThat(order.getUserID()).isEqualTo(USER_ID);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        });
        WIREMOCK.verify(1, putRequestedFor(urlPathMatching(RESERVATION_URL_PATTERN)));
        WIREMOCK.verify(1, putRequestedFor(urlEqualTo("/api/inventory/reservations/" + orderId))
                .withHeader("Authorization", equalTo("Bearer svc-token"))
                .withRequestBody(equalToJson(INVENTORY_BODY)));
    }
}
