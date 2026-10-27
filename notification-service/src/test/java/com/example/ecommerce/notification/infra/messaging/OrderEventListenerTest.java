package com.example.ecommerce.notification.infra.messaging;

import com.example.ecommerce.notification.dto.event.OrderEventDTO;
import com.example.ecommerce.notification.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class OrderEventListenerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final OrderEventListener listener = new OrderEventListener(notificationService);

    @Test
    void createdEventIsDelegatedToNotificationService() {
        OrderEventDTO event = event("CONFIRMED");

        listener.onOrderCreated(event);

        verify(notificationService).handleOrderCreated(event);
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void cancelledEventIsDelegatedToNotificationService() {
        OrderEventDTO event = event("CANCELLED");

        listener.onOrderCancelled(event);

        verify(notificationService).handleOrderCancelled(event);
        verifyNoMoreInteractions(notificationService);
    }

    private OrderEventDTO event(String status) {
        return new OrderEventDTO(UUID.randomUUID(), 2, "order-1", "user-1", status,
                BigDecimal.TEN, LocalDateTime.of(2026, 10, 27, 10, 0));
    }
}
