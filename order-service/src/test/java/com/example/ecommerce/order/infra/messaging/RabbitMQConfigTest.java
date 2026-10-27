package com.example.ecommerce.order.infra.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(RabbitMQConfig.class);

    @Test
    void autoConfiguredTemplateUsesApplicationSettings() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RabbitTemplate.class);
            RabbitTemplate template = context.getBean(RabbitTemplate.class);

            assertThat(template.getMessageConverter())
                    .isSameAs(context.getBean("jsonMessageConverter"))
                    .isInstanceOf(JacksonJsonMessageConverter.class);
            assertThat(template.isMandatoryFor(new Message(new byte[0]))).isTrue();
            assertThat(ReflectionTestUtils.getField(template, "observationEnabled")).isEqualTo(true);
            assertThat(ReflectionTestUtils.getField(template, "retryTemplate")).isInstanceOf(RetryTemplate.class);

            RetryTemplate retryTemplate = (RetryTemplate) ReflectionTestUtils.getField(template, "retryTemplate");
            assertThat(retryTemplate.getRetryPolicy().getBackOff()).isInstanceOf(ExponentialBackOff.class);
            ExponentialBackOff retryBackOff = (ExponentialBackOff) retryTemplate.getRetryPolicy().getBackOff();
            assertThat(retryBackOff.getInitialInterval()).isEqualTo(1000L);
            assertThat(retryBackOff.getMultiplier()).isEqualTo(2.0);
            assertThat(retryBackOff.getMaxInterval()).isEqualTo(5000L);
            BackOffExecution backOff = retryBackOff.start();
            assertThat(backOff.nextBackOff()).isEqualTo(1000L);
            assertThat(backOff.nextBackOff()).isEqualTo(2000L);
            assertThat(backOff.nextBackOff()).isEqualTo(BackOffExecution.STOP);

            CachingConnectionFactory connectionFactory = context.getBean(CachingConnectionFactory.class);
            assertThat(template.getConnectionFactory()).isSameAs(connectionFactory);
            assertThat(connectionFactory.isPublisherConfirms()).isTrue();
            assertThat(connectionFactory.isPublisherReturns()).isTrue();
        });
    }

    @Test
    void autoConfiguredTemplateAppliesCustomizers() {
        contextRunner.withBean(RabbitTemplateCustomizer.class,
                () -> template -> template.setExchange("customized.exchange"))
                .run(context -> assertThat(context.getBean(RabbitTemplate.class).getExchange())
                        .isEqualTo("customized.exchange"));
    }
}
