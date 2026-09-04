package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.entities.OutboxStatus;
import com.example.ecommerce.order_service.repositories.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatcher outboxDispatcher;

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay:5000}")
    public void publishPendingEvents() {
        outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING)
                .forEach(event -> outboxDispatcher.dispatch(event.getId()));
    }
}
