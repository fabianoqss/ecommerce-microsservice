package com.example.ecommerce.order.infra.messaging;

import com.example.ecommerce.order.domain.OutboxStatus;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatcher outboxDispatcher;

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay:5000}")
    public void publishPendingEvents() {
        outboxEventRepository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                        OutboxStatus.PENDING, Instant.now())
                .forEach(event -> outboxDispatcher.dispatch(event.getId()));
    }
}
