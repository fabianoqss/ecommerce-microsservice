package com.example.ecommerce.notification.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class IdempotencyRetryConfiguration implements InitializingBean {

    private final IdempotencyProperties idempotencyProperties;
    private final RabbitProperties rabbitProperties;

    @Override
    public void afterPropertiesSet() {
        var listener = rabbitProperties.getListener();
        var retry = switch (listener.getType()) {
            case SIMPLE -> listener.getSimple().getRetry();
            case DIRECT -> listener.getDirect().getRetry();
            case STREAM -> throw new IllegalStateException("Idempotência exige um listener AMQP com retry");
        };
        Assert.state(retry.isEnabled() && retry.isStateless(),
                "Idempotência exige retry stateless habilitado no listener RabbitMQ");
        var backoff = RetryPolicy.builder()
                .maxRetries(retry.getMaxRetries())
                .delay(retry.getInitialInterval())
                .multiplier(retry.getMultiplier())
                .maxDelay(retry.getMaxInterval())
                .build().getBackOff().start();
        var retryWindow = Duration.ZERO;
        for (long interval = backoff.nextBackOff();
             interval != BackOffExecution.STOP && retryWindow.compareTo(idempotencyProperties.processingTtl()) <= 0;
             interval = backoff.nextBackOff()) {
            retryWindow = retryWindow.plusMillis(interval);
        }
        Assert.state(idempotencyProperties.processingTtl().compareTo(retryWindow) < 0,
                "processing-ttl deve ser menor que a janela total de retry do listener RabbitMQ: processing-ttl="
                        + idempotencyProperties.processingTtl() + ", retry-window=" + retryWindow);
    }
}
