package com.example.ecommerce.notification.infra.redis;

import com.example.ecommerce.notification.config.IdempotencyProperties;
import com.example.ecommerce.notification.service.port.AcquireResult;
import com.example.ecommerce.notification.service.port.AcquireStatus;
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
    private static final DefaultRedisScript<String> ACQUIRE = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
                return 'ACQUIRED'
            end
            if value == 'DONE' then
                return 'ALREADY_PROCESSED'
            end
            return 'IN_PROGRESS'
            """, String.class);
    private static final DefaultRedisScript<Long> MARK_PROCESSED = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value == 'DONE' then
                return 1
            end
            if not value or value == ARGV[1] then
                redis.call('SET', KEYS[1], 'DONE', 'PX', ARGV[2])
                return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final IdempotencyProperties properties;

    @Override
    public AcquireResult tryAcquire(UUID eventId) {
        var ownerToken = UUID.randomUUID();
        var result = redis.execute(ACQUIRE, List.of(key(eventId)), processingValue(ownerToken),
                Long.toString(properties.processingTtl().toMillis()));
        var status = AcquireStatus.valueOf(Objects.requireNonNull(result, "Resultado da aquisição é obrigatório"));
        return new AcquireResult(status, status == AcquireStatus.ACQUIRED ? ownerToken : null);
    }

    @Override
    public boolean markProcessed(UUID eventId, UUID ownerToken) {
        return Long.valueOf(1).equals(redis.execute(MARK_PROCESSED, List.of(key(eventId)), processingValue(ownerToken),
                Long.toString(properties.processedTtl().toMillis())));
    }

    @Override
    public void release(UUID eventId, UUID ownerToken) {
        redis.execute(RELEASE, List.of(key(eventId)), processingValue(ownerToken));
    }

    private String processingValue(UUID ownerToken) {
        return "PROCESSING:" + Objects.requireNonNull(ownerToken, "ownerToken é obrigatório");
    }

    private String key(UUID eventId) {
        return KEY_PREFIX + Objects.requireNonNull(eventId, "eventId é obrigatório");
    }
}
