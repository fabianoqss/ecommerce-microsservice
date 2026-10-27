package com.example.ecommerce.order.infra.messaging;

import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OutboxStatus;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private OutboxDispatcher dispatcher;

    private final MockClock clock = new MockClock();
    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        poller = new OutboxPoller(repository, dispatcher, new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock));
    }

    @Test
    void leavesRemainingEventsForNextRunWhenBudgetIsExhausted() {
        when(repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                eq(OutboxStatus.PENDING), any())).thenReturn(List.of(event(1L), event(2L), event(3L)));
        doAnswer(inv -> {
            clock.add(Duration.ofSeconds(10));
            return null;
        }).when(dispatcher).dispatch(any());

        poller.publishPendingEvents();

        verify(dispatcher).dispatch(1L);
        verify(dispatcher).dispatch(2L);
        verify(dispatcher, never()).dispatch(3L);
    }

    @Test
    void includesDatabaseFetchTimeInConfiguredBudget() {
        poller.setMaxRunDuration(Duration.ofSeconds(2));
        when(repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                eq(OutboxStatus.PENDING), any())).thenAnswer(inv -> {
                    clock.add(Duration.ofSeconds(3));
                    return List.of(event(1L));
                });

        poller.publishPendingEvents();

        verify(dispatcher, never()).dispatch(any());
    }

    private OutboxEvent event(Long id) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        return event;
    }
}
