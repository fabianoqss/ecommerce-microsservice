package com.example.ecommerce.notification.infra.messaging;

import com.example.ecommerce.notification.dto.event.OrderEventDTO;
import com.example.ecommerce.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final NotificationService notificationService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void onOrderCreated(OrderEventDTO event) {
        notificationService.handleOrderCreated(event);
    }

    @RabbitListener(queues = RabbitMQConfig.CANCELLED_QUEUE)
    public void onOrderCancelled(OrderEventDTO event) {
        notificationService.handleOrderCancelled(event);
    }
}
