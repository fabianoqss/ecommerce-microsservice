package com.example.ecommerce.inventory.infra.messaging;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// O order-service é o único dono da topologia; este consumidor só referencia a fila por nome.
@Configuration
public class RabbitMQConfig {

    public static final String ORDER_CANCELLED_QUEUE = "inventory.order.cancelled.queue";

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
