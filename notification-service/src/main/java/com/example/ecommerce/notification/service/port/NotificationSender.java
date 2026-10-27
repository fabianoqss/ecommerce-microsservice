package com.example.ecommerce.notification.service.port;

import com.example.ecommerce.notification.dto.event.OrderEventDTO;

public interface NotificationSender {

    void send(OrderEventDTO event, String notification);
}
