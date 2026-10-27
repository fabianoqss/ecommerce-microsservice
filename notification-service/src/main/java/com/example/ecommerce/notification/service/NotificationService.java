package com.example.ecommerce.notification.service;

import com.example.ecommerce.notification.dto.event.OrderEventDTO;
import com.example.ecommerce.notification.exception.EventInProgressException;
import com.example.ecommerce.notification.service.port.AcquireStatus;
import com.example.ecommerce.notification.service.port.NotificationSender;
import com.example.ecommerce.notification.service.port.ProcessedEventStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final ProcessedEventStore processedEventStore;
    private final NotificationSender notificationSender;
    private final MeterRegistry meterRegistry;

    public void handleOrderCreated(OrderEventDTO event) {
        handle(event, "created", "Notificação de pedido criada");
    }

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

        var acquisition = processedEventStore.tryAcquire(event.eventId());
        if (acquisition.status() == AcquireStatus.ALREADY_PROCESSED) {
            log.info("Evento duplicado ignorado: eventId={}, orderId={}", event.eventId(), event.orderId());
            meterRegistry.counter("ecommerce.notification.events.duplicates", "event_type", eventType).increment();
            return;
        }

        if (acquisition.status() == AcquireStatus.IN_PROGRESS) {
            log.warn("Reentrega de evento em processamento rejeitada para nova tentativa: eventId={}, orderId={}, event_type={}",
                    event.eventId(), event.orderId(), eventType);
            meterRegistry.counter("ecommerce.notification.events.in_progress", "event_type", eventType).increment();
            throw new EventInProgressException(event.eventId());
        }

        try {
            notificationSender.send(event, notification);
        } catch (RuntimeException ex) {
            try {
                processedEventStore.release(event.eventId(), acquisition.ownerToken());
            } catch (RuntimeException releaseFailure) {
                ex.addSuppressed(releaseFailure);
            }
            throw ex;
        }

        if (!processedEventStore.markProcessed(event.eventId(), acquisition.ownerToken())) {
            log.warn("Notificação enviada, mas o lease foi assumido por outro consumidor; pode haver envio duplicado: eventId={}, orderId={}, event_type={}",
                    event.eventId(), event.orderId(), eventType);
            meterRegistry.counter("ecommerce.notification.events.lease_lost", "event_type", eventType).increment();
        }
        processed(eventType);
    }

    private void processed(String eventType) {
        meterRegistry.counter("ecommerce.notification.events.processed", "event_type", eventType).increment();
    }
}
