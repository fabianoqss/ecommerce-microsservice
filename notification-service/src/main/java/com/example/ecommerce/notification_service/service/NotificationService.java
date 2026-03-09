package com.example.ecommerce.notification_service.service;

import com.example.ecommerce.notification_service.dto.OrderEventDTO;
import com.example.ecommerce.notification_service.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void handleOrderCreated(OrderEventDTO event) {
        System.out.println("=== NOTIFICACAO RECEBIDA ===");
        System.out.println("Pedido #" + event.orderId() + " criado com sucesso!");
        System.out.println("Usuario: " + event.userId());
        System.out.println("Status: " + event.status());
        System.out.println("Total: R$" + event.totalValue());
        System.out.println("Data/Hora: " + event.orderTime());
        System.out.println("===========================");
    }
}
