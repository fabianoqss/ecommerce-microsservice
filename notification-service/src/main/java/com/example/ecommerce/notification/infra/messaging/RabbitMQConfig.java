package com.example.ecommerce.notification.infra.messaging;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// O order-service é o único dono da topologia; este consumidor só referencia as filas por nome.
@Configuration
public class RabbitMQConfig {

    public static final String QUEUE = "order.created.queue";
    public static final String CANCELLED_QUEUE = "order.cancelled.queue";

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
