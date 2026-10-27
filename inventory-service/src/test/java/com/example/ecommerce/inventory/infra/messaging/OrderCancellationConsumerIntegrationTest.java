package com.example.ecommerce.inventory.infra.messaging;

import com.example.ecommerce.inventory.domain.Inventory;
import com.example.ecommerce.inventory.domain.ReservationStatus;
import com.example.ecommerce.inventory.domain.StockReservation;
import com.example.ecommerce.inventory.dto.request.InventoryItemDTO;
import com.example.ecommerce.inventory.repository.InventoryRepository;
import com.example.ecommerce.inventory.repository.StockReservationItemRepository;
import com.example.ecommerce.inventory.repository.StockReservationRepository;
import com.example.ecommerce.inventory.service.InventoryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "DB_PASSWORD=unused",
        "spring.rabbitmq.listener.simple.missing-queues-fatal=false",
        "spring.rabbitmq.listener.simple.default-requeue-rejected=false",
        "spring.rabbitmq.listener.simple.retry.enabled=true",
        "spring.rabbitmq.listener.simple.retry.max-attempts=2",
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=1.0",
        "spring.rabbitmq.listener.simple.retry.max-interval=50ms"
})
@Testcontainers
@DirtiesContext
class OrderCancellationConsumerIntegrationTest {

    private static final String VHOST = "ecommerce";
    private static final String CONSUMER_USER = "inventory-service";
    private static final String CONSUMER_PASS = "inventory-secret";
    private static final String EXCHANGE = "order.exchange";
    private static final String CANCELLED_ROUTING_KEY = "order.cancelled";
    private static final String DLX = "order.dlx";
    private static final String INVENTORY_CANCELLED_QUEUE = "inventory.order.cancelled.queue";
    private static final String INVENTORY_CANCELLED_DLQ = "inventory.order.cancelled.dlq";
    private static final String INVENTORY_CANCELLED_DEAD_KEY = "inventory.order.cancelled.dead";

    @org.testcontainers.junit.jupiter.Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.12");

    @org.testcontainers.junit.jupiter.Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.5-management");

    private static CachingConnectionFactory producerConnectionFactory;
    private static RabbitTemplate producerTemplate;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private StockReservationItemRepository reservationItemRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ConnectionFactory consumerConnectionFactory;

    @BeforeAll
    static void provisionBrokerAndOrderTopology() throws Exception {
        execRabbitCtl("add_vhost", VHOST);
        execRabbitCtl("set_permissions", "-p", VHOST, RABBIT.getAdminUsername(), ".*", ".*", ".*");
        execRabbitCtl("add_user", CONSUMER_USER, CONSUMER_PASS);
        execRabbitCtl("set_permissions", "-p", VHOST, CONSUMER_USER,
                "^$", "^$", "^inventory\\.order\\.cancelled\\.queue$");

        producerConnectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        producerConnectionFactory.setVirtualHost(VHOST);
        producerConnectionFactory.setUsername(RABBIT.getAdminUsername());
        producerConnectionFactory.setPassword(RABBIT.getAdminPassword());

        var admin = new RabbitAdmin(producerConnectionFactory);
        var orderExchange = new TopicExchange(EXCHANGE);
        var dlx = new DirectExchange(DLX);
        var cancelledQueue = QueueBuilder.durable(INVENTORY_CANCELLED_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(INVENTORY_CANCELLED_DEAD_KEY)
                .build();
        var cancelledDlq = QueueBuilder.durable(INVENTORY_CANCELLED_DLQ).build();

        admin.declareExchange(orderExchange);
        admin.declareExchange(dlx);
        admin.declareQueue(cancelledQueue);
        admin.declareQueue(cancelledDlq);
        admin.declareBinding(BindingBuilder.bind(cancelledQueue).to(orderExchange).with(CANCELLED_ROUTING_KEY));
        admin.declareBinding(BindingBuilder.bind(cancelledDlq).to(dlx).with(INVENTORY_CANCELLED_DEAD_KEY));

        producerTemplate = new RabbitTemplate(producerConnectionFactory);
        producerTemplate.setMandatory(true);
    }

    @AfterAll
    static void closeProducer() {
        if (producerConnectionFactory != null) {
            producerConnectionFactory.destroy();
        }
    }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.virtual-host", () -> VHOST);
        registry.add("spring.rabbitmq.username", () -> CONSUMER_USER);
        registry.add("spring.rabbitmq.password", () -> CONSUMER_PASS);
    }

    @BeforeEach
    void setUp() {
        reservationItemRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        inventoryRepository.deleteAllInBatch();

        saveSku("SKU-A", 100);
        saveSku("SKU-B", 100);
    }

    @Test
    void ledgerEventReleasesReservationOnceEvenWhenDeliveredTwice() {
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve(orderId, List.of(
                new InventoryItemDTO("SKU-A", 10),
                new InventoryItemDTO("SKU-B", 5)));
        assertThat(quantityOf("SKU-A")).isEqualTo(90);
        assertThat(quantityOf("SKU-B")).isEqualTo(95);

        double releasedBefore = cancellationMetric("ledger", "released");
        double noopBefore = cancellationMetric("ledger", "noop");

        String payload = """
                {
                  "eventId": "%s",
                  "version": 3,
                  "orderId": "%s",
                  "userId": "user-123",
                  "status": "CANCELLED",
                  "totalValue": 50.00,
                  "orderTime": "2026-10-20T12:00:00",
                  "reservationMode": "LEDGER",
                  "items": [
                    {"skuCode": "SKU-A", "quantity": 10},
                    {"skuCode": "SKU-B", "quantity": 5}
                  ]
                }
                """.formatted(UUID.randomUUID(), orderId);

        publishJson(payload);
        publishJson(payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(cancellationMetric("ledger", "released")).isEqualTo(releasedBefore + 1.0);
            assertThat(cancellationMetric("ledger", "noop")).isEqualTo(noopBefore + 1.0);
        });

        assertThat(quantityOf("SKU-A")).isEqualTo(100);
        assertThat(quantityOf("SKU-B")).isEqualTo(100);
        StockReservation reservation = reservationRepository.findById(orderId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void legacyEventIncrementsItemsOnceEvenWhenDeliveredTwice() {
        UUID orderId = UUID.randomUUID();
        double releasedBefore = cancellationMetric("legacy", "released");
        double noopBefore = cancellationMetric("legacy", "noop");

        String payload = """
                {
                  "eventId": "%s",
                  "version": 3,
                  "orderId": "%s",
                  "userId": "user-legacy",
                  "status": "CANCELLED",
                  "totalValue": 75.00,
                  "orderTime": "2026-10-20T12:00:00",
                  "reservationMode": "LEGACY",
                  "items": [
                    {"skuCode": "SKU-B", "quantity": 4},
                    {"skuCode": "SKU-A", "quantity": 7}
                  ]
                }
                """.formatted(UUID.randomUUID(), orderId);

        publishJson(payload);
        publishJson(payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(cancellationMetric("legacy", "released")).isEqualTo(releasedBefore + 1.0);
            assertThat(cancellationMetric("legacy", "noop")).isEqualTo(noopBefore + 1.0);
        });

        assertThat(quantityOf("SKU-A")).isEqualTo(107);
        assertThat(quantityOf("SKU-B")).isEqualTo(104);
        StockReservation tombstone = reservationRepository.findById(orderId).orElseThrow();
        assertThat(tombstone.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservationItemRepository.findByIdReservationIdOrderByIdSkuCodeAsc(orderId)).isEmpty();
    }

    @Test
    void version2EventIsAckedAndIgnoredWithoutTouchingStock() {
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve(orderId, List.of(new InventoryItemDTO("SKU-A", 6)));
        assertThat(quantityOf("SKU-A")).isEqualTo(94);

        double ignoredBefore = cancellationMetric("ignored", "ignored");

        String v2Payload = """
                {
                  "eventId": "%s",
                  "version": 2,
                  "orderId": "%s",
                  "userId": "user-v2",
                  "status": "CANCELLED",
                  "totalValue": 30.00,
                  "orderTime": "2026-10-20T12:00:00"
                }
                """.formatted(UUID.randomUUID(), orderId);

        publishJson(v2Payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(cancellationMetric("ignored", "ignored")).isEqualTo(ignoredBefore + 1.0));

        assertThat(quantityOf("SKU-A")).isEqualTo(94);
        assertThat(reservationRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void ledgerEventAfterReleaseAlreadyDoneIsNoop() {
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve(orderId, List.of(new InventoryItemDTO("SKU-A", 8)));
        inventoryService.release(orderId);
        assertThat(quantityOf("SKU-A")).isEqualTo(100);

        double releasedBefore = cancellationMetric("ledger", "released");
        double noopBefore = cancellationMetric("ledger", "noop");

        String payload = """
                {
                  "eventId": "%s",
                  "version": 3,
                  "orderId": "%s",
                  "userId": "user-123",
                  "status": "CANCELLED",
                  "totalValue": 40.00,
                  "orderTime": "2026-10-20T12:00:00",
                  "reservationMode": "LEDGER",
                  "items": [{"skuCode": "SKU-A", "quantity": 8}]
                }
                """.formatted(UUID.randomUUID(), orderId);

        publishJson(payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(cancellationMetric("ledger", "noop")).isEqualTo(noopBefore + 1.0));

        assertThat(cancellationMetric("ledger", "released")).isEqualTo(releasedBefore);
        assertThat(quantityOf("SKU-A")).isEqualTo(100);
    }

    @Test
    void consumerUserHasMinimalPermissionsAndDeadLettersInvalidV3Payload() {
        var consumerTemplate = new RabbitTemplate(consumerConnectionFactory);

        assertThatThrownBy(() -> consumerTemplate.execute(channel ->
                channel.queueDeclare(INVENTORY_CANCELLED_QUEUE, true, false, false, null)))
                .isInstanceOf(AmqpIOException.class)
                .rootCause()
                .hasMessageContaining("ACCESS_REFUSED");

        assertThatThrownBy(() -> consumerTemplate.execute(channel -> channel.basicGet(INVENTORY_CANCELLED_DLQ, true)))
                .isInstanceOf(AmqpIOException.class)
                .rootCause()
                .hasMessageContaining("ACCESS_REFUSED");

        UUID badEventId = UUID.randomUUID();
        publishJson("""
                {"eventId":"%s","version":3,"reservationMode":"LEDGER"}
                """.formatted(badEventId));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message deadLettered = producerTemplate.receive(INVENTORY_CANCELLED_DLQ);
            assertThat(deadLettered).isNotNull();
            assertThat(new String(deadLettered.getBody(), StandardCharsets.UTF_8))
                    .contains(badEventId.toString());
        });
    }

    private void publishJson(String json) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
        producerTemplate.send(EXCHANGE, CANCELLED_ROUTING_KEY, message);
    }

    private void saveSku(String sku, int quantity) {
        Inventory inv = new Inventory();
        inv.setSkuCode(sku);
        inv.setQuantity(quantity);
        inventoryRepository.save(inv);
    }

    private int quantityOf(String sku) {
        return inventoryRepository.findBySkuCodeIn(List.of(sku)).getFirst().getQuantity();
    }

    private double cancellationMetric(String mode, String result) {
        return meterRegistry.counter("inventory.cancellations", "mode", mode, "result", result).count();
    }

    private static void execRabbitCtl(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(args, 0, command, 1, args.length);
        Container.ExecResult result = RABBIT.execInContainer(command);
        assertThat(result.getExitCode())
                .as("rabbitmqctl %s failed: %s %s", String.join(" ", args), result.getStdout(), result.getStderr())
                .isZero();
    }
}
