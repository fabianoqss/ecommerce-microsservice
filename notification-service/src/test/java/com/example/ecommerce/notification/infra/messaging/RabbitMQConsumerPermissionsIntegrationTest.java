package com.example.ecommerce.notification.infra.messaging;

import com.example.ecommerce.notification.dto.OrderEventDTO;
import com.example.ecommerce.notification.service.NotificationSender;
import com.example.ecommerce.notification.service.ProcessedEventStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = RabbitMQConsumerPermissionsIntegrationTest.TestConfig.class,
        properties = {
                "spring.rabbitmq.listener.simple.missing-queues-fatal=false",
                "spring.rabbitmq.listener.simple.default-requeue-rejected=false",
                "spring.rabbitmq.listener.simple.retry.enabled=true",
                "spring.rabbitmq.listener.simple.retry.max-attempts=2",
                "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
                "spring.rabbitmq.listener.simple.retry.multiplier=1.0",
                "spring.rabbitmq.listener.simple.retry.max-interval=50ms"
        }
)
@Testcontainers
@DirtiesContext
class RabbitMQConsumerPermissionsIntegrationTest {

    private static final String VHOST = "ecommerce";
    private static final String CONSUMER_USER = "notification-service";
    private static final String CONSUMER_PASS = "notification-secret";
    private static final String EXCHANGE = "order.exchange";
    private static final String CREATED_ROUTING_KEY = "order.created";
    private static final String CANCELLED_ROUTING_KEY = "order.cancelled";
    private static final String DLX = "order.dlx";
    private static final String CREATED_DLQ = "order.created.dlq";
    private static final String CREATED_DEAD_KEY = "order.created.dead";
    private static final String CANCELLED_DLQ = "order.cancelled.dlq";
    private static final String CANCELLED_DEAD_KEY = "order.cancelled.dead";

    @org.testcontainers.junit.jupiter.Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.5-management");

    private static CachingConnectionFactory producerConnectionFactory;
    private static RabbitTemplate producerTemplate;

    @Autowired
    private ProcessedEventStore processedEventStore;

    @Autowired
    private NotificationSender notificationSender;

    @Autowired
    private MessageConverter messageConverter;

    @Autowired
    private ConnectionFactory consumerConnectionFactory;

    @BeforeAll
    static void provisionBrokerAndOrderTopology() throws Exception {
        execRabbitCtl("add_vhost", VHOST);
        execRabbitCtl("set_permissions", "-p", VHOST, RABBIT.getAdminUsername(), ".*", ".*", ".*");
        execRabbitCtl("add_user", CONSUMER_USER, CONSUMER_PASS);
        execRabbitCtl("set_permissions", "-p", VHOST, CONSUMER_USER,
                "^$", "^$", "^order\\.(created|cancelled)\\.queue$");

        producerConnectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        producerConnectionFactory.setVirtualHost(VHOST);
        producerConnectionFactory.setUsername(RABBIT.getAdminUsername());
        producerConnectionFactory.setPassword(RABBIT.getAdminPassword());

        var admin = new RabbitAdmin(producerConnectionFactory);
        var orderExchange = new TopicExchange(EXCHANGE);
        var dlx = new DirectExchange(DLX);
        var createdQueue = QueueBuilder.durable(RabbitMQConfig.QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(CREATED_DEAD_KEY)
                .build();
        var createdDlq = QueueBuilder.durable(CREATED_DLQ).build();
        var cancelledQueue = QueueBuilder.durable(RabbitMQConfig.CANCELLED_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(CANCELLED_DEAD_KEY)
                .build();
        var cancelledDlq = QueueBuilder.durable(CANCELLED_DLQ).build();

        admin.declareExchange(orderExchange);
        admin.declareExchange(dlx);
        admin.declareQueue(createdQueue);
        admin.declareQueue(createdDlq);
        admin.declareQueue(cancelledQueue);
        admin.declareQueue(cancelledDlq);
        admin.declareBinding(BindingBuilder.bind(createdQueue).to(orderExchange).with(CREATED_ROUTING_KEY));
        admin.declareBinding(BindingBuilder.bind(createdDlq).to(dlx).with(CREATED_DEAD_KEY));
        admin.declareBinding(BindingBuilder.bind(cancelledQueue).to(orderExchange).with(CANCELLED_ROUTING_KEY));
        admin.declareBinding(BindingBuilder.bind(cancelledDlq).to(dlx).with(CANCELLED_DEAD_KEY));
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
    void resetMocks() {
        reset(processedEventStore, notificationSender);
        when(processedEventStore.tryAcquire(any())).thenReturn(true);
        producerTemplate = new RabbitTemplate(producerConnectionFactory);
        producerTemplate.setMessageConverter(messageConverter);
        producerTemplate.setMandatory(true);
    }

    @Test
    void consumesCreatedAndCancelledEventsAndDeadLettersOnFailureWithMinimalPermissions() {
        var failingEvent = event(UUID.randomUUID(), "CONFIRMED");
        doThrow(new IllegalStateException("Falha simulada no envio"))
                .when(notificationSender).send(eq(failingEvent), anyString());

        producerTemplate.convertAndSend(EXCHANGE, CREATED_ROUTING_KEY, failingEvent);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OrderEventDTO deadLettered = producerTemplate.receiveAndConvert(
                    CREATED_DLQ, ParameterizedTypeReference.forType(OrderEventDTO.class));
            assertThat(deadLettered).isNotNull();
            assertThat(deadLettered.eventId()).isEqualTo(failingEvent.eventId());
        });

        var createdEvent = event(UUID.randomUUID(), "CONFIRMED");
        var cancelledEvent = event(UUID.randomUUID(), "CANCELLED");
        producerTemplate.convertAndSend(EXCHANGE, CREATED_ROUTING_KEY, createdEvent);
        producerTemplate.convertAndSend(EXCHANGE, CANCELLED_ROUTING_KEY, cancelledEvent);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(notificationSender).send(eq(createdEvent), anyString());
            verify(processedEventStore).markProcessed(createdEvent.eventId());
            verify(notificationSender).send(eq(cancelledEvent), anyString());
            verify(processedEventStore).markProcessed(cancelledEvent.eventId());
        });
    }

    @Test
    void consumerUserCannotConfigureQueuesOrReadDlq() {
        var consumerTemplate = new RabbitTemplate(consumerConnectionFactory);

        assertThatThrownBy(() -> consumerTemplate.execute(channel ->
                channel.queueDeclare(RabbitMQConfig.QUEUE, true, false, false, null)))
                .isInstanceOf(AmqpIOException.class)
                .rootCause()
                .hasMessageContaining("ACCESS_REFUSED");

        assertThatThrownBy(() -> consumerTemplate.execute(channel -> channel.basicGet(CREATED_DLQ, true)))
                .isInstanceOf(AmqpIOException.class)
                .rootCause()
                .hasMessageContaining("ACCESS_REFUSED");
    }

    private static OrderEventDTO event(UUID eventId, String status) {
        return new OrderEventDTO(
                eventId,
                2,
                UUID.randomUUID().toString(),
                "user-123",
                status,
                new BigDecimal("49.90"),
                LocalDateTime.of(2026, 10, 20, 12, 0)
        );
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

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import({RabbitMQConfig.class, NotificationService.class})
    static class TestConfig {

        @Bean
        ProcessedEventStore processedEventStore() {
            return mock(ProcessedEventStore.class);
        }

        @Bean
        NotificationSender notificationSender() {
            return mock(NotificationSender.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
