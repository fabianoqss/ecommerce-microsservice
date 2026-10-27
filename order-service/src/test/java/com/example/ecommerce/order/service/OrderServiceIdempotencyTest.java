package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.entity.OrderItem;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.dto.client.product.ProductDTO;
import com.example.ecommerce.order.dto.request.OrderItemRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.exception.IdempotencyKeyReuseException;
import com.example.ecommerce.order.exception.OrderInProgressException;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.infra.client.ProductClient;
import com.example.ecommerce.order.infra.tracing.OutboxTraceContext;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceIdempotencyTest {

    private static final String USER_ID = "owner-sub";
    private static final UUID ORDER_ID = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("a4ee662f-e404-4cf5-afc0-d837d0beaf82");
    private static final OrderRequestDTO ORDER = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 1)));

    @Mock
    private ProductClient productClient;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Spy
    private ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private OutboxTraceContext outboxTraceContext = new OutboxTraceContext(Tracer.NOOP, Propagator.NOOP);

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
    }

    @Test
    void confirmedOrderWithSameKeyAndHashIsReplayedWithoutCallingClientsOrSaving() {
        Order existing = existingOrder(OrderStatus.CONFIRMED);
        when(orderRepository.findByUserIDAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        var result = orderService.createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.orderResponseDTO().id()).isEqualTo(ORDER_ID);
        assertThat(result.orderResponseDTO().status()).isEqualTo("CONFIRMED");
        assertThat(result.orderResponseDTO().totalValue()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(result.orderResponseDTO().items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo("p1");
            assertThat(item.quantity()).isEqualTo(1);
        });
        verify(orderRepository).findByUserIDAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY);
        verify(orderRepository, never()).saveAndFlush(any());
        verifyNoInteractions(productClient, inventoryClient, outboxEventRepository);
        assertThat(meterRegistry.counter("orders.idempotency", "result", "replayed").count()).isEqualTo(1.0);
    }

    @Test
    void sameKeyWithDifferentHashRejectsPayloadBeforeCallingClientsOrSaving() {
        Order existing = existingOrder(OrderStatus.CONFIRMED);
        when(orderRepository.findByUserIDAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));
        var differentOrder = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 2)));

        assertThatThrownBy(() -> orderService.createOrder(differentOrder, USER_ID, IDEMPOTENCY_KEY))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verifyNoInteractions(productClient, inventoryClient, outboxEventRepository);
        assertThat(meterRegistry.counter("orders.idempotency", "result", "payload_mismatch").count()).isEqualTo(1.0);
    }

    @Test
    void pendingOrderWithSameKeyAndHashReportsExistingOrderId() {
        when(orderRepository.findByUserIDAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existingOrder(OrderStatus.PENDING)));

        assertThatThrownBy(() -> orderService.createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY))
                .isInstanceOfSatisfying(OrderInProgressException.class,
                        exception -> assertThat(exception.getOrderId()).isEqualTo(ORDER_ID));

        verify(orderRepository, never()).saveAndFlush(any());
        verifyNoInteractions(productClient, inventoryClient, outboxEventRepository);
        assertThat(meterRegistry.counter("orders.idempotency", "result", "in_progress").count()).isEqualTo(1.0);
    }

    @Test
    void persistenceRaceReplaysConfirmedOrderFoundBySecondLookupWithoutReservingInventory() {
        when(orderRepository.findByUserIDAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty(), Optional.of(existingOrder(OrderStatus.CONFIRMED)));
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(orderRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Duplicate key"));

        var result = orderService.createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.orderResponseDTO().id()).isEqualTo(ORDER_ID);
        assertThat(result.orderResponseDTO().status()).isEqualTo("CONFIRMED");
        verify(orderRepository, times(2)).findByUserIDAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY);
        verify(orderRepository).saveAndFlush(any());
        verifyNoInteractions(inventoryClient, outboxEventRepository);
        assertThat(meterRegistry.counter("orders.idempotency", "result", "race").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("orders.idempotency", "result", "replayed").count()).isEqualTo(1.0);
    }

    @Test
    void persistenceFailureWithoutReplayRethrowsSameException() {
        when(orderRepository.findByUserIDAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty(), Optional.empty());
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        var failure = new DataIntegrityViolationException("Pending order persistence failed");
        when(orderRepository.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> orderService.createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY)).isSameAs(failure);

        verify(orderRepository, times(2)).findByUserIDAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY);
        verify(orderRepository).saveAndFlush(any());
        verifyNoInteractions(inventoryClient, outboxEventRepository);
        assertThat(meterRegistry.counter("orders.idempotency", "result", "race").count()).isEqualTo(1.0);
    }

    @Test
    void equivalentCartsWithDifferentItemOrderAndGroupingPersistSameRequestHash() {
        stubSuccessfulCreation();
        var duplicated = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 1),
                new OrderItemRequestDTO("p2", 2),
                new OrderItemRequestDTO("p1", 1)));
        var aggregated = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p2", 2), new OrderItemRequestDTO("p1", 2)));

        orderService.createOrder(duplicated, USER_ID, IDEMPOTENCY_KEY);
        orderService.createOrder(aggregated, USER_ID, UUID.randomUUID());

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(4)).saveAndFlush(captor.capture());
        String firstHash = captor.getAllValues().get(0).getRequestHash();
        String secondHash = captor.getAllValues().get(2).getRequestHash();
        assertThat(firstHash).matches("[0-9a-f]{64}").isEqualTo(secondHash);
    }

    @Test
    void cartsWithDifferentQuantitiesPersistDifferentRequestHashes() {
        stubSuccessfulCreation();
        var first = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 1), new OrderItemRequestDTO("p2", 2)));
        var second = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 2), new OrderItemRequestDTO("p2", 2)));

        orderService.createOrder(first, USER_ID, IDEMPOTENCY_KEY);
        orderService.createOrder(second, USER_ID, UUID.randomUUID());

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(4)).saveAndFlush(captor.capture());
        String firstHash = captor.getAllValues().get(0).getRequestHash();
        String secondHash = captor.getAllValues().get(2).getRequestHash();
        assertThat(firstHash).matches("[0-9a-f]{64}").isNotEqualTo(secondHash);
        assertThat(secondHash).matches("[0-9a-f]{64}");
    }

    private void stubSuccessfulCreation() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(productClient.getProductById("p2")).thenReturn(new ProductDTO("p2", "Mouse", BigDecimal.ONE));
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(UUID.randomUUID());
            }
            return order;
        });
    }

    private static Order existingOrder(OrderStatus status) {
        OrderItem item = new OrderItem();
        item.setProductId("p1");
        item.setProductName("Teclado");
        item.setQuantity(1);
        item.setPrice(BigDecimal.TEN);
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserID(USER_ID);
        order.setIdempotencyKey(IDEMPOTENCY_KEY);
        order.setRequestHash("975270bba1f750e5ddc9eed01b70a96ffe2cc74c90ae58cd474212ae16c53642");
        order.setStatus(status);
        order.setOrderTime(LocalDateTime.of(2026, 10, 27, 10, 0));
        order.setTotalValue(BigDecimal.TEN);
        order.setItems(List.of(item));
        return order;
    }
}
