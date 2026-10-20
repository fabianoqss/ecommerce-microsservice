package com.example.ecommerce.order_service.enums;

import com.example.ecommerce.order_service.config.RabbitMQConfig;

public enum OrderEventType {
    ORDER_CREATED(RabbitMQConfig.ROUTING_KEY),
    ORDER_CANCELLED(RabbitMQConfig.CANCELLED_ROUTING_KEY);

    private final String routingKey;

    OrderEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
