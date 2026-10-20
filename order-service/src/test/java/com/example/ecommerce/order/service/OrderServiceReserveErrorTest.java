package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.Order;
import com.example.ecommerce.order.domain.OrderStatus;
import com.example.ecommerce.order.dto.request.OrderItemRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.dto.response.ProductDTO;
import com.example.ecommerce.order.exception.InsufficientStockException;
import com.example.ecommerce.order.exception.OrderReservationException;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.infra.client.ProductClient;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceReserveErrorTest {

    private static final UUID ORDER_ID = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");
    private static final Request RESERVE = Request.create(Request.HttpMethod.PUT,
            "/api/inventory/reservations/" + ORDER_ID, Map.of(), null, StandardCharsets.UTF_8, null);
    private static final OrderRequestDTO ORDER = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 1)));

    @Mock
    private ProductClient productClient;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @Spy
    private TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private OrderService orderService;

    private Order persistedOrder;

    @BeforeEach
    void setUp() {
        orderService.setReservationRetryDelay(Duration.ZERO);
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(ORDER_ID);
            }
            persistedOrder = order;
            return order;
        });
    }

    @Test
    void conflictCancelsPendingOrderWithoutRetryAndReportsInsufficientStock() {
        doThrow(new FeignException.Conflict("conflict", RESERVE, null, null))
                .when(inventoryClient).reserve(eq(ORDER_ID), any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(InsufficientStockException.class);

        verify(inventoryClient, times(1)).reserve(eq(ORDER_ID), any());
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(persistedOrder.getCancellationReason()).isEqualTo("Estoque insuficiente");
        assertThat(persistedOrder.getConfirmedAt()).isNull();
        verifyNoInteractions(outboxEventRepository);
        assertThat(meterRegistry.counter("orders.created", "result", "insufficient_stock").count()).isEqualTo(1.0);
    }

    @Test
    void serverErrorRetriesTwiceAndLeavesOrderPendingWith503Exception() {
        doThrow(new FeignException.ServiceUnavailable("503", RESERVE, null, null))
                .when(inventoryClient).reserve(eq(ORDER_ID), any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(OrderReservationException.class);

        // 1 chamada inicial + 2 retentativas = 3 tentativas no total.
        verify(inventoryClient, times(3)).reserve(eq(ORDER_ID), any());
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(outboxEventRepository);
        assertThat(meterRegistry.counter("orders.created", "result", "pending").count()).isEqualTo(1.0);
    }

    @Test
    void timeoutRetriesTwiceAndLeavesOrderPending() {
        doThrow(new RetryableException(
                -1, "Read timed out", Request.HttpMethod.PUT, (Date) null, RESERVE))
                .when(inventoryClient).reserve(eq(ORDER_ID), any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(OrderReservationException.class);

        verify(inventoryClient, times(3)).reserve(eq(ORDER_ID), any());
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(outboxEventRepository);
        assertThat(meterRegistry.counter("orders.created", "result", "pending").count()).isEqualTo(1.0);
    }

    @Test
    void gone410DoesNotRetryAndLeavesOrderPendingWith503Exception() {
        doThrow(new FeignException.Gone("already released", RESERVE, null, null))
                .when(inventoryClient).reserve(eq(ORDER_ID), any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(OrderReservationException.class);

        verify(inventoryClient, times(1)).reserve(eq(ORDER_ID), any());
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(outboxEventRepository);
        assertThat(meterRegistry.counter("orders.created", "result", "pending").count()).isEqualTo(1.0);
    }

    @Test
    void unprocessableEntity422DoesNotRetryAndLeavesOrderPendingWith503Exception() {
        doThrow(new FeignException.UnprocessableEntity("divergent items", RESERVE, null, null))
                .when(inventoryClient).reserve(eq(ORDER_ID), any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(OrderReservationException.class);

        verify(inventoryClient, times(1)).reserve(eq(ORDER_ID), any());
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(outboxEventRepository);
        assertThat(meterRegistry.counter("orders.created", "result", "pending").count()).isEqualTo(1.0);
    }

    @Test
    void reserveForbiddenIsNotReportedAsInsufficientStockAndDoesNotRetry() {
        doThrow(new FeignException.Forbidden("forbidden", RESERVE, null, null))
                .when(inventoryClient).reserve(eq(ORDER_ID), any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(OrderReservationException.class)
                .hasCauseInstanceOf(FeignException.Forbidden.class);

        verify(inventoryClient, times(1)).reserve(eq(ORDER_ID), any());
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void reserveUnauthorizedIsNotReportedAsInsufficientStockAndDoesNotRetry() {
        doThrow(new FeignException.Unauthorized("unauthorized", RESERVE, null, null))
                .when(inventoryClient).reserve(eq(ORDER_ID), any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(OrderReservationException.class)
                .hasCauseInstanceOf(FeignException.Unauthorized.class);

        verify(inventoryClient, times(1)).reserve(eq(ORDER_ID), any());
        assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }
}
