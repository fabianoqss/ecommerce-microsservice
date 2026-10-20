package com.example.ecommerce.notification_service.service;

import com.example.ecommerce.notification_service.dto.OrderEventDTO;

public interface NotificationSender {

    void send(OrderEventDTO event, String notification);
}
