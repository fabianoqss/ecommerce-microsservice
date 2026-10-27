package com.example.ecommerce.notification.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyRetryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "spring.rabbitmq.listener.simple.retry.enabled=true",
                    "spring.rabbitmq.listener.simple.retry.max-retries=7",
                    "spring.rabbitmq.listener.simple.retry.initial-interval=1s",
                    "spring.rabbitmq.listener.simple.retry.multiplier=2",
                    "spring.rabbitmq.listener.simple.retry.max-interval=10s");

    @Test
    void applicationDefaultsAllowLeaseRecovery() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void leaseShorterThanFortyFiveSecondWindowStarts() {
        runner.withPropertyValues("ecommerce.notification.idempotency.processing-ttl=44999ms")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @Timeout(5)
    void hugeRetryCountStopsSummingOnceWindowExceedsLease() {
        runner.withPropertyValues("spring.rabbitmq.listener.simple.retry.max-retries=" + Integer.MAX_VALUE)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"45s", "46s", "5m"})
    void leaseAtLeastAsLongAsRetryWindowFailsStartup(String ttl) {
        runner.withPropertyValues("ecommerce.notification.idempotency.processing-ttl=" + ttl)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("processing-ttl")
                        .hasStackTraceContaining("PT45S"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"enabled=false", "max-retries=0", "max-retries=4", "stateless=false"})
    void retryThatCannotRecoverLeaseFailsStartup(String property) {
        runner.withPropertyValues("spring.rabbitmq.listener.simple.retry." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void fixedBackoffUsesAllRetries() {
        runner.withPropertyValues(
                        "spring.rabbitmq.listener.simple.retry.max-retries=4",
                        "spring.rabbitmq.listener.simple.retry.initial-interval=500ms",
                        "spring.rabbitmq.listener.simple.retry.multiplier=1",
                        "ecommerce.notification.idempotency.processing-ttl=1999ms")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(
                        "spring.rabbitmq.listener.simple.retry.max-retries=4",
                        "spring.rabbitmq.listener.simple.retry.initial-interval=500ms",
                        "spring.rabbitmq.listener.simple.retry.multiplier=1",
                        "ecommerce.notification.idempotency.processing-ttl=2s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void fractionalMultiplierAndIntervalCapAreRespected() {
        runner.withPropertyValues(
                        "spring.rabbitmq.listener.simple.retry.max-retries=3",
                        "spring.rabbitmq.listener.simple.retry.multiplier=1.5",
                        "spring.rabbitmq.listener.simple.retry.max-interval=2s",
                        "ecommerce.notification.idempotency.processing-ttl=4499ms")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(
                        "spring.rabbitmq.listener.simple.retry.max-retries=3",
                        "spring.rabbitmq.listener.simple.retry.multiplier=1.5",
                        "spring.rabbitmq.listener.simple.retry.max-interval=2s",
                        "ecommerce.notification.idempotency.processing-ttl=4500ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void directListenerUsesItsOwnRetryProperties() {
        runner.withPropertyValues(
                        "spring.rabbitmq.listener.type=direct",
                        "spring.rabbitmq.listener.direct.retry.enabled=true",
                        "spring.rabbitmq.listener.direct.retry.max-retries=4",
                        "spring.rabbitmq.listener.direct.retry.initial-interval=10s")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.rabbitmq.listener.type=direct")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({IdempotencyProperties.class, RabbitProperties.class})
    @Import(IdempotencyRetryConfiguration.class)
    static class PropertiesConfiguration {
    }
}
