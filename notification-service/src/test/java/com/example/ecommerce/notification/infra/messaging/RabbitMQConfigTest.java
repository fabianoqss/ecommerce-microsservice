package com.example.ecommerce.notification.infra.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    @Test
    void consumerOnlyProvidesJsonConverterWithoutDeclaringTopology() {
        try (var context = new AnnotationConfigApplicationContext(RabbitMQConfig.class)) {
            assertThat(context.getBeansOfType(Declarable.class)).isEmpty();
            assertThat(context.getBean(MessageConverter.class)).isInstanceOf(JacksonJsonMessageConverter.class);
        }
    }
}
