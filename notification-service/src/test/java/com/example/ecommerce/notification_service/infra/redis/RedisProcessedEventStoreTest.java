package com.example.ecommerce.notification_service.infra.redis;

import com.example.ecommerce.notification_service.config.IdempotencyProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class RedisProcessedEventStoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-alpine3.21")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void acquisitionIsExclusiveAndUsesProcessingTtl() {
        var store = store(Duration.ofMinutes(5), Duration.ofDays(7));
        var eventId = UUID.randomUUID();

        assertThat(store.tryAcquire(eventId)).isTrue();
        assertThat(store.tryAcquire(eventId)).isFalse();
        assertThat(redis.opsForValue().get(key(eventId))).isEqualTo("PROCESSING");
        assertThat(redis.getExpire(key(eventId), MILLISECONDS)).isBetween(1L, 300_000L);
    }

    @Test
    void concurrentAcquireHasOnlyOneWinner() throws Exception {
        var store = store(Duration.ofMinutes(5), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = IntStream.range(0, 16)
                    .mapToObj(i -> (Callable<Boolean>) () -> store.tryAcquire(eventId)).toList();
            int acquired = 0;
            for (var result : executor.invokeAll(attempts)) {
                if (result.get()) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        }
    }

    @Test
    void releaseAllowsRetry() {
        var store = store(Duration.ofMinutes(5), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        store.tryAcquire(eventId);

        store.release(eventId);

        assertThat(redis.hasKey(key(eventId))).isFalse();
        assertThat(store.tryAcquire(eventId)).isTrue();
    }

    @Test
    void completionSurvivesNewStoreAndCannotBeReleased() {
        var store = store(Duration.ofMinutes(5), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        store.tryAcquire(eventId);

        store.markProcessed(eventId);
        store.release(eventId);

        assertThat(redis.opsForValue().get(key(eventId))).isEqualTo("DONE");
        assertThat(redis.getExpire(key(eventId), MILLISECONDS)).isBetween(600_000L, 604_800_000L);
        assertThat(store(Duration.ofMinutes(5), Duration.ofDays(7)).tryAcquire(eventId)).isFalse();
    }

    @Test
    void abandonedAcquisitionExpires() {
        var store = store(Duration.ofMillis(150), Duration.ofSeconds(5));
        var eventId = UUID.randomUUID();
        store.tryAcquire(eventId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.hasKey(key(eventId))).isFalse());

        assertThat(store.tryAcquire(eventId)).isTrue();
    }

    @Test
    void deduplicationWindowExpires() {
        var store = store(Duration.ofMillis(150), Duration.ofMillis(300));
        var eventId = UUID.randomUUID();
        store.tryAcquire(eventId);
        store.markProcessed(eventId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.hasKey(key(eventId))).isFalse());

        assertThat(store.tryAcquire(eventId)).isTrue();
    }

    @Test
    void releaseOfMissingEventDoesNotCreateKey() {
        var store = store(Duration.ofMinutes(5), Duration.ofDays(7));
        var eventId = UUID.randomUUID();

        store.release(eventId);

        assertThat(redis.hasKey(key(eventId))).isFalse();
    }

    private RedisProcessedEventStore store(Duration processingTtl, Duration processedTtl) {
        return new RedisProcessedEventStore(redis, new IdempotencyProperties(processingTtl, processedTtl));
    }

    private String key(UUID eventId) {
        return "ecommerce:notification:event:" + eventId;
    }
}
