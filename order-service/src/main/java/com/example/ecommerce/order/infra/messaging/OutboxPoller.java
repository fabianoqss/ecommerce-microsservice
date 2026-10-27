package com.example.ecommerce.order.infra.messaging;

import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OutboxStatus;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatcher outboxDispatcher;

    private final MeterRegistry meterRegistry;

    @Setter
    @Value("${ecommerce.order.outbox.max-run-duration:20s}")
    private Duration maxRunDuration = Duration.ofSeconds(20);

    @Scheduled(scheduler = "outboxPollerScheduler", fixedDelayString = "${app.outbox.fixed-delay:5000}")
    public void publishPendingEvents() {
        long startedAt = meterRegistry.config().clock().monotonicTime();
        List<OutboxEvent> events = outboxEventRepository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                        OutboxStatus.PENDING, Instant.now());
        for (OutboxEvent event : events) {
            if (meterRegistry.config().clock().monotonicTime() - startedAt >= maxRunDuration.toNanos()) {
                break;
            }
            outboxDispatcher.dispatch(event.getId());
        }
    }
}
