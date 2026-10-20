package com.example.ecommerce.notification.service;

import com.example.ecommerce.notification.dto.OrderEventDTO;

public interface NotificationSender {

    void send(OrderEventDTO event, String notification);
}
