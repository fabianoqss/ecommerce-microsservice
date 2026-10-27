package com.example.ecommerce.notification.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsBoundProcessingAndDeduplicationWindows() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(IdempotencyProperties.class);
            assertThat(properties.processingTtl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.processedTtl()).isEqualTo(Duration.ofDays(7));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"processing-ttl=0ms", "processing-ttl=-1s", "processed-ttl=0ms",
            "processed-ttl=-1d", "processing-ttl=PT0.000001S", "processed-ttl=invalid"})
    void invalidTtlFailsStartup(String property) {
        runner.withPropertyValues("ecommerce.notification.idempotency." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void customWindowsAreBound() {
        runner.withPropertyValues("ecommerce.notification.idempotency.processing-ttl=2m",
                        "ecommerce.notification.idempotency.processed-ttl=3d")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(IdempotencyProperties.class);
                    assertThat(properties.processingTtl()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.processedTtl()).isEqualTo(Duration.ofDays(3));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdempotencyProperties.class)
    static class PropertiesConfiguration {
    }
}
