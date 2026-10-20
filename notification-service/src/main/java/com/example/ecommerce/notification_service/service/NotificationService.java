package com.example.ecommerce.notification_service.service;

import com.example.ecommerce.notification_service.dto.OrderEventDTO;
import com.example.ecommerce.notification_service.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final IdempotencyGuard idempotencyGuard;

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void handleOrderCreated(OrderEventDTO event) {
        handle(event, "Notificação de pedido criada");
    }

    @RabbitListener(queues = RabbitMQConfig.CANCELLED_QUEUE)
    public void handleOrderCancelled(OrderEventDTO event) {
        handle(event, "Notificação de pedido cancelado");
    }

    private void handle(OrderEventDTO event, String notification) {
        if (event.eventId() == null) {
            log.warn("Evento legado sem eventId recebido; será processado sem garantia de idempotência: orderId={}", event.orderId());
            notify(event, notification);
            return;
        }

        if (!idempotencyGuard.tryAcquire(event.eventId())) {
            log.info("Evento duplicado ignorado: eventId={}, orderId={}", event.eventId(), event.orderId());
            return;
        }

        try {
            notify(event, notification);
        } catch (RuntimeException ex) {
            idempotencyGuard.release(event.eventId());
            throw ex;
        }
    }

    private void notify(OrderEventDTO event, String notification) {
        log.info(
                "{}: eventId={}, version={}, orderId={}, userId={}, status={}, total={}, orderTime={}",
                notification, event.eventId(), event.version(), event.orderId(), event.userId(), event.status(), event.totalValue(), event.orderTime()
        );
    }
}
