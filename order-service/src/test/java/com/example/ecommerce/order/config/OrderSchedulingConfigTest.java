package com.example.ecommerce.order.config;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.infra.messaging.OutboxDispatcher;
import com.example.ecommerce.order.infra.messaging.OutboxPoller;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import com.example.ecommerce.order.service.OrderReconciler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderSchedulingConfigTest {

    @Test
    void scheduledJobsUseIsolatedThreadsEvenWhileOutboxIsBlocked() throws Exception {
        OrderRepository orders = mock(OrderRepository.class);
        OutboxEventRepository events = mock(OutboxEventRepository.class);
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        InventoryClient inventory = mock(InventoryClient.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.EXPIRED);
        when(orders.findReconcilableOrdersForUpdate(any(), anyInt())).thenReturn(List.of(order));
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        when(events.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(any(), any()))
                .thenReturn(List.of(event));
        CountDownLatch outboxStarted = new CountDownLatch(1);
        CountDownLatch reconcilerStarted = new CountDownLatch(1);
        CountDownLatch releaseOutbox = new CountDownLatch(1);
        AtomicReference<String> outboxThread = new AtomicReference<>();
        AtomicReference<String> reconcilerThread = new AtomicReference<>();
        doAnswer(inv -> {
            outboxThread.set(Thread.currentThread().getName());
            outboxStarted.countDown();
            assertThat(releaseOutbox.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(dispatcher).dispatch(1L);
        doAnswer(inv -> {
            reconcilerThread.set(Thread.currentThread().getName());
            reconcilerStarted.countDown();
            return null;
        }).when(inventory).releaseReservation(order.getId());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.registerBean(OrderRepository.class, () -> orders);
            context.registerBean(OutboxEventRepository.class, () -> events);
            context.registerBean(OutboxDispatcher.class, () -> dispatcher);
            context.registerBean(InventoryClient.class, () -> inventory);
            context.registerBean(TransactionTemplate.class, () -> new TransactionTemplate(transactions));
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(SchedulingTestConfig.class);
            try {
                context.refresh();
                assertThat(outboxStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(reconcilerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(outboxThread.get()).startsWith("outbox-poller-");
                assertThat(reconcilerThread.get()).startsWith("order-reconciler-");
                assertThat(outboxThread.get()).isNotEqualTo(reconcilerThread.get());
            } finally {
                releaseOutbox.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"outboxPollerScheduler", "orderReconcilerScheduler"})
    void shutdownWaitsForInFlightTask(String schedulerName) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closingStarted = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(OrderSchedulingConfig.class);
             var closer = Executors.newSingleThreadExecutor()) {
            ThreadPoolTaskScheduler scheduler = context.getBean(schedulerName, ThreadPoolTaskScheduler.class);
            scheduler.execute(() -> {
                started.countDown();
                try {
                    if (release.await(5, TimeUnit.SECONDS)) {
                        finished.set(true);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var closing = closer.submit(() -> {
                    closingStarted.countDown();
                    context.close();
                });
                assertThat(closingStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                closing.get(5, TimeUnit.SECONDS);
                assertThat(finished).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import({OrderSchedulingConfig.class, OutboxPoller.class, OrderReconciler.class})
    static class SchedulingTestConfig {
    }
}
