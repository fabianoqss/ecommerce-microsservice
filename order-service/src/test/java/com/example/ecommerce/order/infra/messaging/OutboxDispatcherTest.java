package com.example.ecommerce.order.infra.messaging;

import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OutboxStatus;
import com.example.ecommerce.order.dto.event.OrderEventDTO;
import com.example.ecommerce.order.infra.tracing.OutboxTraceContext;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanReporter;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
        dispatcher = new OutboxDispatcher(outboxEventRepository, rabbitTemplate, objectMapper, meterRegistry,
                new OutboxTraceContext(Tracer.NOOP, Propagator.NOOP));
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

    @Test
    void dispatchFailureIsReportedOnTheRestoredTrace() throws Exception {
        List<FinishedSpan> spans = new ArrayList<>();
        OutboxEvent event = samplePendingEvent();
        event.setTraceparent("00-463ac35c9f6413ad48485a3953bb6124-a2fb4a1d1a96d312-01");
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));
        var failure = new AmqpException("broker down");
        doThrow(failure).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BraveAutoConfiguration.class))
                .withUserConfiguration(OutboxTraceContext.class)
                .withBean(SpanReporter.class, () -> spans::add)
                .run(context -> {
                    var tracedDispatcher = new OutboxDispatcher(outboxEventRepository, rabbitTemplate,
                            objectMapper, meterRegistry, context.getBean(OutboxTraceContext.class));

                    tracedDispatcher.dispatch(1L);

                    assertThat(spans).singleElement().satisfies(span -> {
                        assertThat(span.getName()).isEqualTo("outbox.dispatch");
                        assertThat(span.getTraceId()).isEqualTo("463ac35c9f6413ad48485a3953bb6124");
                        assertThat(span.getError()).isSameAs(failure);
                    });
                    assertThat(context.getBean(Tracer.class).currentSpan()).isNull();
                });
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
