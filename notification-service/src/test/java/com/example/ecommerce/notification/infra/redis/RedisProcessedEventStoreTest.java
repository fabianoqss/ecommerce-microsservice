package com.example.ecommerce.notification.infra.redis;

import com.example.ecommerce.notification.config.IdempotencyProperties;
import com.example.ecommerce.notification.service.port.AcquireResult;
import com.example.ecommerce.notification.service.port.AcquireStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        var store = store(Duration.ofSeconds(30), Duration.ofDays(7));
        var eventId = UUID.randomUUID();

        var acquisition = store.tryAcquire(eventId);

        assertThat(acquisition.status()).isEqualTo(AcquireStatus.ACQUIRED);
        assertThat(acquisition.ownerToken()).isNotNull();
        assertThat(store.tryAcquire(eventId)).isEqualTo(new AcquireResult(AcquireStatus.IN_PROGRESS, null));
        assertThat(redis.opsForValue().get(key(eventId))).isEqualTo("PROCESSING:" + acquisition.ownerToken());
        assertThat(redis.getExpire(key(eventId), MILLISECONDS)).isBetween(1L, 30_000L);
    }

    @Test
    void concurrentAcquireHasOnlyOneWinner() throws Exception {
        var store = store(Duration.ofSeconds(30), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = IntStream.range(0, 16)
                    .mapToObj(i -> (Callable<AcquireResult>) () -> store.tryAcquire(eventId)).toList();
            int acquired = 0;
            for (var result : executor.invokeAll(attempts)) {
                if (result.get().status() == AcquireStatus.ACQUIRED) {
                    acquired++;
                } else {
                    assertThat(result.get().status()).isEqualTo(AcquireStatus.IN_PROGRESS);
                }
            }
            assertThat(acquired).isEqualTo(1);
        }
    }

    @Test
    void releaseAllowsRetryWithNewOwnerToken() {
        var store = store(Duration.ofSeconds(30), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        var acquisition = store.tryAcquire(eventId);

        store.release(eventId, acquisition.ownerToken());

        assertThat(redis.hasKey(key(eventId))).isFalse();
        var next = store.tryAcquire(eventId);
        assertThat(next.status()).isEqualTo(AcquireStatus.ACQUIRED);
        assertThat(next.ownerToken()).isNotEqualTo(acquisition.ownerToken());
    }

    @Test
    void completionSurvivesNewStoreAndCannotBeReleased() {
        var store = store(Duration.ofSeconds(30), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        var acquisition = store.tryAcquire(eventId);

        assertThat(store.markProcessed(eventId, acquisition.ownerToken())).isTrue();
        store.release(eventId, acquisition.ownerToken());

        assertThat(redis.opsForValue().get(key(eventId))).isEqualTo("DONE");
        assertThat(redis.getExpire(key(eventId), MILLISECONDS)).isBetween(600_000L, 604_800_000L);
        assertThat(store(Duration.ofSeconds(30), Duration.ofDays(7)).tryAcquire(eventId))
                .isEqualTo(new AcquireResult(AcquireStatus.ALREADY_PROCESSED, null));
        assertThat(store.markProcessed(eventId, acquisition.ownerToken())).isTrue();
        assertThat(redis.opsForValue().get(key(eventId))).isEqualTo("DONE");
    }

    @Test
    void abandonedAcquisitionExpiresAndCanBeRecovered() {
        var store = store(Duration.ofMillis(150), Duration.ofSeconds(5));
        var eventId = UUID.randomUUID();
        var abandoned = store.tryAcquire(eventId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.hasKey(key(eventId))).isFalse());

        var recovered = store.tryAcquire(eventId);
        assertThat(recovered.status()).isEqualTo(AcquireStatus.ACQUIRED);
        assertThat(recovered.ownerToken()).isNotEqualTo(abandoned.ownerToken());
        assertThat(store.markProcessed(eventId, recovered.ownerToken())).isTrue();
        assertThat(store.tryAcquire(eventId).status()).isEqualTo(AcquireStatus.ALREADY_PROCESSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"markProcessed", "release"})
    void staleOwnerCannotChangeAnotherOwnersLease(String operation) {
        var store = store(Duration.ofMillis(150), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        var stale = store.tryAcquire(eventId);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.hasKey(key(eventId))).isFalse());
        var nextStore = store(Duration.ofSeconds(30), Duration.ofDays(7));
        var current = nextStore.tryAcquire(eventId);

        if (operation.equals("markProcessed")) {
            assertThat(store.markProcessed(eventId, stale.ownerToken())).isFalse();
        } else {
            store.release(eventId, stale.ownerToken());
        }

        assertThat(redis.opsForValue().get(key(eventId))).isEqualTo("PROCESSING:" + current.ownerToken());
        assertThat(redis.getExpire(key(eventId), MILLISECONDS)).isBetween(1L, 30_000L);
        assertThat(nextStore.tryAcquire(eventId).status()).isEqualTo(AcquireStatus.IN_PROGRESS);
        assertThat(nextStore.markProcessed(eventId, current.ownerToken())).isTrue();
        store.release(eventId, stale.ownerToken());
        assertThat(nextStore.tryAcquire(eventId).status()).isEqualTo(AcquireStatus.ALREADY_PROCESSED);
    }

    @Test
    void deduplicationWindowExpires() {
        var store = store(Duration.ofSeconds(30), Duration.ofMillis(300));
        var eventId = UUID.randomUUID();
        var acquisition = store.tryAcquire(eventId);
        assertThat(store.markProcessed(eventId, acquisition.ownerToken())).isTrue();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.hasKey(key(eventId))).isFalse());

        assertThat(store.tryAcquire(eventId).status()).isEqualTo(AcquireStatus.ACQUIRED);
    }

    @Test
    void ownerWhoseLeaseExpiredWithoutCompetitionRecordsCompletion() {
        var store = store(Duration.ofMillis(150), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        var acquisition = store.tryAcquire(eventId);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.hasKey(key(eventId))).isFalse());

        store.release(eventId, acquisition.ownerToken());
        assertThat(redis.hasKey(key(eventId))).isFalse();

        assertThat(store.markProcessed(eventId, acquisition.ownerToken())).isTrue();
        assertThat(redis.opsForValue().get(key(eventId))).isEqualTo("DONE");
        assertThat(store.tryAcquire(eventId).status()).isEqualTo(AcquireStatus.ALREADY_PROCESSED);
    }

    @Test
    void legacyProcessingValueIsRejectedForRetry() {
        var store = store(Duration.ofSeconds(30), Duration.ofDays(7));
        var eventId = UUID.randomUUID();
        redis.opsForValue().set(key(eventId), "PROCESSING", Duration.ofSeconds(30));

        assertThat(store.tryAcquire(eventId).status()).isEqualTo(AcquireStatus.IN_PROGRESS);
        store.release(eventId, UUID.randomUUID());
        assertThat(redis.opsForValue().get(key(eventId))).isEqualTo("PROCESSING");
    }

    private RedisProcessedEventStore store(Duration processingTtl, Duration processedTtl) {
        return new RedisProcessedEventStore(redis, new IdempotencyProperties(processingTtl, processedTtl));
    }

    private String key(UUID eventId) {
        return "ecommerce:notification:event:" + eventId;
    }
}
