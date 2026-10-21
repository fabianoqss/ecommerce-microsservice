package com.example.ecommerce.order.infra.messaging;

import com.example.ecommerce.order.domain.OutboxEvent;
import com.example.ecommerce.order.domain.OutboxStatus;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OutboxDispatcher(outboxEventRepository, rabbitTemplate, objectMapper, meterRegistry);
        dispatcher.setBaseBackoff(Duration.ofSeconds(5));
        dispatcher.setMaxBackoff(Duration.ofSeconds(60));
        dispatcher.setMaxAttempts(100);
    }

    @Test
    void exponentialBackoffRespectsBaseAndCeiling() {
        assertThat(dispatcher.computeBackoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(dispatcher.computeBackoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(dispatcher.computeBackoff(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(dispatcher.computeBackoff(4)).isEqualTo(Duration.ofSeconds(40));
        assertThat(dispatcher.computeBackoff(5)).isEqualTo(Duration.ofSeconds(60));
        assertThat(dispatcher.computeBackoff(50)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void failureSchedulesNextAttemptWithExponentialBackoffWhileBelowMaxAttempts() throws Exception {
        OutboxEvent event = samplePendingEvent();
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        Instant before = Instant.now();
        dispatcher.dispatch(1L);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(5));
        assertThat(meterRegistry.find("outbox.failed").counter()).isNull();
    }

    @Test
    void marksFailedAndIncrementsMetricWhenReachingMaxAttempts() throws Exception {
        dispatcher.setMaxAttempts(3);
        OutboxEvent event = samplePendingEvent();
        event.setAttempts(2);
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        dispatcher.dispatch(1L);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(meterRegistry.counter("outbox.failed", "type", "ORDER_CREATED").count()).isEqualTo(1.0);
    }

    private OutboxEvent samplePendingEvent() throws Exception {
        UUID orderId = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");
        UUID eventId = UUID.randomUUID();
        OrderEventDTO dto = new OrderEventDTO(
                eventId, 2, orderId, "user-sub", "CONFIRMED", BigDecimal.TEN, LocalDateTime.now(), null, null);
        OutboxEvent event = OutboxEvent.pending(eventId, orderId, "ORDER_CREATED", objectMapper.writeValueAsString(dto));
        event.setId(1L);
        return event;
    }
}
