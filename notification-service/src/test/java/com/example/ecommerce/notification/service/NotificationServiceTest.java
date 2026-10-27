package com.example.ecommerce.notification.service;

import com.example.ecommerce.notification.dto.event.OrderEventDTO;
import com.example.ecommerce.notification.service.port.NotificationSender;
import com.example.ecommerce.notification.service.port.ProcessedEventStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private final ProcessedEventStore store = mock(ProcessedEventStore.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotificationService service = new NotificationService(store, sender, registry);

    @BeforeEach
    void acquire() {
        when(store.tryAcquire(any())).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "cancelled"})
    void successfulNotificationIsPersistedAndCounted(String type) {
        var event = event(UUID.randomUUID());

        handle(type, event);

        var order = inOrder(store, sender);
        order.verify(store).tryAcquire(event.eventId());
        order.verify(sender).send(eq(event), anyString());
        order.verify(store).markProcessed(event.eventId());
        assertThat(count("ecommerce.notification.events.processed", type)).isEqualTo(1);
        assertThat(count("ecommerce.notification.events.duplicates", type)).isZero();
        verify(store, never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "cancelled"})
    void duplicateIsIgnoredAndCounted(String type) {
        var event = event(UUID.randomUUID());
        when(store.tryAcquire(event.eventId())).thenReturn(false);

        handle(type, event);

        verifyNoInteractions(sender);
        verify(store, never()).markProcessed(any());
        verify(store, never()).release(any());
        assertThat(count("ecommerce.notification.events.duplicates", type)).isEqualTo(1);
        assertThat(count("ecommerce.notification.events.processed", type)).isZero();
    }

    @Test
    void notificationFailureReleasesForRetry() {
        var event = event(UUID.randomUUID());
        var failure = new IllegalStateException("Falha ao notificar");
        doThrow(failure).when(sender).send(eq(event), anyString());

        assertThatThrownBy(() -> service.handleOrderCreated(event)).isSameAs(failure);

        verify(store).release(event.eventId());
        verify(store, never()).markProcessed(any());
        assertThat(count("ecommerce.notification.events.processed", "created")).isZero();
    }

    @Test
    void completionFailurePropagatesForRetry() {
        var event = event(UUID.randomUUID());
        var failure = new RedisConnectionFailureException("Redis indisponível");
        doThrow(failure).when(store).markProcessed(event.eventId());

        assertThatThrownBy(() -> service.handleOrderCreated(event)).isSameAs(failure);

        verify(store).release(event.eventId());
        assertThat(count("ecommerce.notification.events.processed", "created")).isZero();
    }

    @Test
    void releaseFailurePreservesOriginalFailure() {
        var event = event(UUID.randomUUID());
        var failure = new IllegalStateException("Falha ao notificar");
        var releaseFailure = new RedisConnectionFailureException("Redis indisponível");
        doThrow(failure).when(sender).send(eq(event), anyString());
        doThrow(releaseFailure).when(store).release(event.eventId());

        assertThatThrownBy(() -> service.handleOrderCancelled(event))
                .isSameAs(failure).hasSuppressedException(releaseFailure);
    }

    @Test
    void unavailableStoreDoesNotSendNotification() {
        var failure = new RedisConnectionFailureException("Redis indisponível");
        when(store.tryAcquire(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.handleOrderCreated(event(UUID.randomUUID()))).isSameAs(failure);

        verifyNoInteractions(sender);
        verify(store, never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "cancelled"})
    void legacyEventWithoutIdIsStillNotified(String type) {
        var event = event(null);

        handle(type, event);

        verify(sender).send(eq(event), anyString());
        verifyNoInteractions(store);
        assertThat(count("ecommerce.notification.events.processed", type)).isEqualTo(1);
    }

    private double count(String name, String type) {
        var counter = registry.find(name).tag("event_type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    private void handle(String type, OrderEventDTO event) {
        if (type.equals("created")) {
            service.handleOrderCreated(event);
        } else {
            service.handleOrderCancelled(event);
        }
    }

    private OrderEventDTO event(UUID eventId) {
        return new OrderEventDTO(eventId, 2, "order-123", "user-123", "CREATED",
                new BigDecimal("19.90"), LocalDateTime.of(2026, 10, 20, 10, 0));
    }
}
