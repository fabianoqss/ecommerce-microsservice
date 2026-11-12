package com.example.ecommerce.order.infra.messaging;

import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OrderEventType;
import com.example.ecommerce.order.domain.enums.OutboxStatus;
import com.example.ecommerce.order.dto.event.OrderEventDTO;
import com.example.ecommerce.order.infra.tracing.OutboxTraceContext;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxDispatcher {

    private static final int CONFIRM_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final OutboxTraceContext outboxTraceContext;

    @Setter
    @Value("${ecommerce.order.outbox.base-backoff:5s}")
    private Duration baseBackoff = Duration.ofSeconds(5);

    @Setter
    @Value("${ecommerce.order.outbox.max-backoff:30m}")
    private Duration maxBackoff = Duration.ofMinutes(30);

    @Setter
    @Value("${ecommerce.order.outbox.max-attempts:100}")
    private int maxAttempts = 100;

    @Transactional
    public void dispatch(Long outboxId) {
        OutboxEvent outbox = outboxEventRepository.findById(outboxId).orElse(null);
        if (outbox == null || outbox.getStatus() != OutboxStatus.PENDING) {
            return;
        }

        try (var traceScope = outboxTraceContext.restore(outbox.getTraceparent())) {
            try {
                OrderEventDTO event = objectMapper.readValue(outbox.getPayload(), OrderEventDTO.class);
                CorrelationData correlationData = new CorrelationData(outbox.getEventId().toString());

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EXCHANGE,
                        OrderEventType.valueOf(outbox.getEventType()).routingKey(),
                        event,
                        correlationData
                );

                CorrelationData.Confirm confirm = correlationData.getFuture()
                        .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!confirm.ack()) {
                    throw new IllegalStateException("RabbitMQ rejected the event: " + confirm.reason());
                }
                if (correlationData.getReturned() != null) {
                    throw new IllegalStateException("Unrouted event: " + correlationData.getReturned().getReplyText());
                }

                outbox.setStatus(OutboxStatus.SENT);
                outbox.setSentAt(LocalDateTime.now());
                outbox.setLastError(null);
                log.info("Order event published: eventId={}, type={}, orderId={}",
                        outbox.getEventId(), outbox.getEventType(), outbox.getAggregateId());
            } catch (Exception ex) {
                traceScope.error(ex);
                int attempts = outbox.getAttempts() + 1;
                outbox.setAttempts(attempts);
                outbox.setLastError(limit(ex.getMessage()));
                if (attempts >= maxAttempts) {
                    outbox.setStatus(OutboxStatus.FAILED);
                    meterRegistry.counter("outbox.failed", "type", outbox.getEventType()).increment();
                    log.error("Event moved to FAILED after {} attempts: eventId={}, orderId={}",
                            attempts, outbox.getEventId(), outbox.getAggregateId(), ex);
                } else {
                    Duration backoff = computeBackoff(attempts);
                    Instant nextAttempt = Instant.now().plus(backoff);
                    outbox.setNextAttemptAt(nextAttempt);
                    log.warn("Failed to publish event (attempt {}/{}), next attempt at {}: eventId={}, orderId={}",
                            attempts, maxAttempts, nextAttempt, outbox.getEventId(), outbox.getAggregateId(), ex);
                }
            }
        }
    }

    Duration computeBackoff(int attempts) {
        long baseMillis = Math.max(1L, baseBackoff.toMillis());
        long maxMillis = Math.max(baseMillis, maxBackoff.toMillis());
        int shift = Math.min(Math.max(0, attempts - 1), 30);
        long delayMillis;
        try {
            delayMillis = Math.multiplyExact(baseMillis, 1L << shift);
        } catch (ArithmeticException ex) {
            delayMillis = maxMillis;
        }
        return Duration.ofMillis(Math.min(delayMillis, maxMillis));
    }

    private String limit(String message) {
        if (message == null) {
            return "Error without a message";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
