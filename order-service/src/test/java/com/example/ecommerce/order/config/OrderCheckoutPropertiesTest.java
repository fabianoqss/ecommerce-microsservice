package com.example.ecommerce.order.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCheckoutPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void defaultQuantityLimitMatchesInventoryContract() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OrderCheckoutProperties.class).maxQuantityPerProduct()).isEqualTo(10000);
        });
    }

    @Test
    void quantityLimitCanBeConfigured() {
        contextRunner.withPropertyValues("ecommerce.order.checkout.max-quantity-per-product=25")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OrderCheckoutProperties.class).maxQuantityPerProduct()).isEqualTo(25);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 10001})
    void invalidQuantityLimitFailsStartup(int limit) {
        contextRunner.withPropertyValues("ecommerce.order.checkout.max-quantity-per-product=" + limit)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderCheckoutProperties.class)
    static class PropertiesConfig {
    }
}
