package com.example.ecommerce.notification.infra.messaging;

import com.example.ecommerce.notification.dto.OrderEventDTO;
import com.example.ecommerce.notification.service.NotificationSender;
import com.example.ecommerce.notification.service.ProcessedEventStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final ProcessedEventStore processedEventStore;
    private final NotificationSender notificationSender;
    private final MeterRegistry meterRegistry;

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void handleOrderCreated(OrderEventDTO event) {
        handle(event, "created", "Notificação de pedido criada");
    }

    @RabbitListener(queues = RabbitMQConfig.CANCELLED_QUEUE)
    public void handleOrderCancelled(OrderEventDTO event) {
        handle(event, "cancelled", "Notificação de pedido cancelado");
    }

    private void handle(OrderEventDTO event, String eventType, String notification) {
        if (event.eventId() == null) {
            log.warn("Evento legado sem eventId recebido; será processado sem garantia de idempotência: orderId={}", event.orderId());
            notificationSender.send(event, notification);
            processed(eventType);
            return;
        }

        if (!processedEventStore.tryAcquire(event.eventId())) {
            log.info("Evento duplicado ignorado: eventId={}, orderId={}", event.eventId(), event.orderId());
            meterRegistry.counter("ecommerce.notification.events.duplicates", "event_type", eventType).increment();
            return;
        }

        try {
            notificationSender.send(event, notification);
            processedEventStore.markProcessed(event.eventId());
        } catch (RuntimeException ex) {
            try {
                processedEventStore.release(event.eventId());
            } catch (RuntimeException releaseFailure) {
                // Preserva a causa original sem esconder a indisponibilidade do Redis.
                ex.addSuppressed(releaseFailure);
            }
            throw ex;
        }
        processed(eventType);
    }

    private void processed(String eventType) {
        meterRegistry.counter("ecommerce.notification.events.processed", "event_type", eventType).increment();
    }
}
