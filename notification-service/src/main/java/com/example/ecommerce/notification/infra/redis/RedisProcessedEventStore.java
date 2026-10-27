package com.example.ecommerce.notification.infra.redis;

import com.example.ecommerce.notification.config.IdempotencyProperties;
import com.example.ecommerce.notification.service.port.ProcessedEventStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisProcessedEventStore implements ProcessedEventStore {

    private static final String KEY_PREFIX = "ecommerce:notification:event:";
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == 'PROCESSING' then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final IdempotencyProperties properties;

    @Override
    public boolean tryAcquire(UUID eventId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(eventId), "PROCESSING", properties.processingTtl()));
    }

    @Override
    public void markProcessed(UUID eventId) {
        redis.opsForValue().set(key(eventId), "DONE", properties.processedTtl());
    }

    @Override
    public void release(UUID eventId) {
        redis.execute(RELEASE, List.of(key(eventId)));
    }

    private String key(UUID eventId) {
        return KEY_PREFIX + Objects.requireNonNull(eventId, "eventId é obrigatório");
    }
}
