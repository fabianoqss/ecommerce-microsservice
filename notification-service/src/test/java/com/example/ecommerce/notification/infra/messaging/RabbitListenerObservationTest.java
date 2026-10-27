package com.example.ecommerce.notification.infra.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitListenerObservationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withPropertyValues("DB_PASSWORD=unused");

    @Test
    void applicationEnablesObservationForAutoConfiguredListeners() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var factory = context.getBean(SimpleRabbitListenerContainerFactory.class);
            var endpoint = new SimpleRabbitListenerEndpoint();
            endpoint.setId("observed-listener");
            endpoint.setQueueNames("orders");
            endpoint.setMessageListener(message -> { });
            SimpleMessageListenerContainer container = factory.createListenerContainer(endpoint);

            assertThat(ReflectionTestUtils.getField(container, "observationEnabled")).isEqualTo(true);
        });
    }
}
