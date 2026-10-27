package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.domain.enums.ReservationMode;
import com.example.ecommerce.order.dto.client.inventory.InventoryItemDTO;
import com.example.ecommerce.order.dto.client.inventory.InventoryReserveRequestDTO;
import com.example.ecommerce.order.dto.client.product.ProductDTO;
import com.example.ecommerce.order.dto.request.OrderItemRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.exception.InvalidProductDataException;
import com.example.ecommerce.order.exception.OrderReservationException;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.infra.client.ProductClient;
import com.example.ecommerce.order.infra.tracing.OutboxTraceContext;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import feign.FeignException;
import feign.Request;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceCreateTest {

    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("a4ee662f-e404-4cf5-afc0-d837d0beaf82");
    private static final UUID ORDER_ID = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");
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
    private ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private OutboxTraceContext outboxTraceContext = new OutboxTraceContext(Tracer.NOOP, Propagator.NOOP);

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService.setReservationRetryDelay(Duration.ZERO);
    }

    @Test
    void nullPriceFailsBeforeReserve() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", null));

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "owner-sub", IDEMPOTENCY_KEY))
                .isInstanceOf(InvalidProductDataException.class)
                .hasMessageContaining("product-service").hasMessageContaining("p1");

        verifyNoInteractions(inventoryClient, outboxEventRepository);
        verify(orderRepository).findByUserIDAndIdempotencyKey("owner-sub", IDEMPOTENCY_KEY);
        verifyNoMoreInteractions(orderRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void invalidNameFailsBeforeReserve(String name) {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", name, BigDecimal.TEN));

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "owner-sub", IDEMPOTENCY_KEY))
                .isInstanceOf(InvalidProductDataException.class)
                .hasMessageContaining("product-service").hasMessageContaining("p1");

        verifyNoInteractions(inventoryClient, outboxEventRepository);
        verify(orderRepository).findByUserIDAndIdempotencyKey("owner-sub", IDEMPOTENCY_KEY);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    void invalidLaterProductFailsBeforeReserve() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(productClient.getProductById("p2")).thenReturn(new ProductDTO("p2", "Mouse", null));
        var request = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 1), new OrderItemRequestDTO("p2", 2)));

        assertThatThrownBy(() -> orderService.createOrder(request, "owner-sub", IDEMPOTENCY_KEY))
                .isInstanceOf(InvalidProductDataException.class)
                .hasMessageContaining("product-service").hasMessageContaining("p2");

        verifyNoInteractions(inventoryClient, outboxEventRepository);
        verify(orderRepository).findByUserIDAndIdempotencyKey("owner-sub", IDEMPOTENCY_KEY);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    void pendingPersistenceFailureRollsBackWithoutCallingInventory() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        var transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus(), transaction, new SimpleTransactionStatus());
        var failure = new DataIntegrityViolationException("Falha no flush do PENDING");
        when(orderRepository.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "owner-sub", IDEMPOTENCY_KEY)).isSameAs(failure);

        var calls = inOrder(transactionManager, orderRepository);
        calls.verify(transactionManager).getTransaction(any());
        calls.verify(orderRepository).findByUserIDAndIdempotencyKey("owner-sub", IDEMPOTENCY_KEY);
        calls.verify(transactionManager).commit(any());
        calls.verify(transactionManager).getTransaction(any());
        calls.verify(orderRepository).saveAndFlush(any());
        calls.verify(transactionManager).rollback(transaction);
        calls.verify(transactionManager).getTransaction(any());
        calls.verify(orderRepository).findByUserIDAndIdempotencyKey("owner-sub", IDEMPOTENCY_KEY);
        calls.verify(transactionManager).commit(any());
        verifyNoInteractions(inventoryClient, outboxEventRepository);
    }

    @Test
    void persistsPendingBeforeReserveAndConfirmsWithOutboxAfterSuccess() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(productClient.getProductById("p2")).thenReturn(new ProductDTO("p2", "Mouse", new BigDecimal("20")));
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());

        List<OrderStatus> savedStatuses = new ArrayList<>();
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(ORDER_ID);
            }
            savedStatuses.add(order.getStatus());
            return order;
        });

        var response = orderService.createOrder(duplicatedItems(), "owner-sub", IDEMPOTENCY_KEY).orderResponseDTO();

        var calls = inOrder(productClient, transactionManager, orderRepository, inventoryClient, outboxEventRepository);
        calls.verify(transactionManager).getTransaction(any());
        calls.verify(orderRepository).findByUserIDAndIdempotencyKey("owner-sub", IDEMPOTENCY_KEY);
        calls.verify(transactionManager).commit(any());
        calls.verify(productClient).getProductById("p2");
        calls.verify(productClient).getProductById("p1");
        calls.verify(transactionManager).getTransaction(any());
        ArgumentCaptor<Order> pendingCaptor = ArgumentCaptor.forClass(Order.class);
        calls.verify(orderRepository).saveAndFlush(pendingCaptor.capture());
        calls.verify(transactionManager).commit(any());
        calls.verify(inventoryClient).reserve(eq(ORDER_ID), eq(new InventoryReserveRequestDTO(List.of(
                new InventoryItemDTO("p1", 4), new InventoryItemDTO("p2", 2)))));
        calls.verify(transactionManager).getTransaction(any());
        calls.verify(orderRepository).saveAndFlush(any());
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        calls.verify(outboxEventRepository).save(outbox.capture());
        calls.verify(transactionManager).commit(any());
        verify(inventoryClient, never()).releaseReservation(any());
        verifyNoMoreInteractions(productClient);

        assertThat(savedStatuses).containsExactly(OrderStatus.PENDING, OrderStatus.CONFIRMED);
        assertThat(pendingCaptor.getValue().getReservationMode()).isEqualTo(ReservationMode.LEDGER);
        assertThat(pendingCaptor.getValue().getConfirmedAt()).isNotNull();
        assertThat(pendingCaptor.getValue().getItems()).extracting(item -> item.getProductId()).containsExactly("p2", "p1", "p1");
        assertThat(pendingCaptor.getValue().getItems()).extracting(item -> item.getQuantity()).containsExactly(2, 1, 3);
        assertThat(response.id()).isEqualTo(ORDER_ID);
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.totalValue()).isEqualByComparingTo("80");
        assertThat(response.items()).hasSize(3);
        assertThat(outbox.getValue().getAggregateId()).isEqualTo(ORDER_ID);
        assertThat(outbox.getValue().getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(meterRegistry.counter("orders.created", "result", "confirmed").count()).isEqualTo(1.0);
    }

    @Test
    void transientServerErrorOnFirstReserveAttemptSucceedsOnRetryAndConfirms() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(ORDER_ID);
            return order;
        });
        Request reserveReq = Request.create(Request.HttpMethod.PUT, "/api/inventory/reservations/" + ORDER_ID,
                Map.of(), null, StandardCharsets.UTF_8, null);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(inv -> {
            if (attempts.incrementAndGet() < 3) {
                throw new FeignException.BadGateway("502", reserveReq, null, null);
            }
            return null;
        }).when(inventoryClient).reserve(eq(ORDER_ID), any());

        var response = orderService.createOrder(ORDER, "owner-sub", IDEMPOTENCY_KEY).orderResponseDTO();

        assertThat(response.status()).isEqualTo("CONFIRMED");
        verify(inventoryClient, times(3)).reserve(eq(ORDER_ID), any());
        verify(outboxEventRepository).save(any());
        assertThat(meterRegistry.counter("orders.created", "result", "confirmed").count()).isEqualTo(1.0);
    }

    @Test
    void optimisticLockFailureWhenConfirmingBecauseReconcilerExpiredThrows503ReservationException() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        AtomicInteger saves = new AtomicInteger();
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (saves.incrementAndGet() == 1) {
                order.setId(ORDER_ID);
                return order;
            }
            throw new ObjectOptimisticLockingFailureException(Order.class, ORDER_ID);
        });

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "owner-sub", IDEMPOTENCY_KEY))
                .isInstanceOf(OrderReservationException.class);

        verifyNoInteractions(outboxEventRepository);
        assertThat(meterRegistry.counter("orders.created", "result", "pending").count()).isEqualTo(1.0);
    }

    @Test
    void responseIsMappedBeforeConfirmationTransactionCloses() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        var commits = new AtomicInteger();
        var confirmationCommitted = new AtomicBoolean();
        doAnswer(invocation -> {
            if (commits.incrementAndGet() == 3) {
                confirmationCommitted.set(true);
            }
            return null;
        }).when(transactionManager).commit(any());

        AtomicInteger saves = new AtomicInteger();
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(ORDER_ID);
            if (saves.incrementAndGet() == 1) {
                return order;
            }
            var items = order.getItems();
            var persisted = spy(order);
            doAnswer(call -> {
                if (confirmationCommitted.get()) {
                    throw new org.hibernate.LazyInitializationException("Sessão encerrada");
                }
                return items;
            }).when(persisted).getItems();
            return persisted;
        });

        var response = orderService.createOrder(ORDER, "owner-sub", IDEMPOTENCY_KEY).orderResponseDTO();

        assertThat(confirmationCommitted).isTrue();
        assertThat(response.id()).isEqualTo(ORDER_ID);
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo("p1");
            assertThat(item.quantity()).isEqualTo(1);
        });
    }

    @Test
    void quantityOverflowFailsBeforeReserve() {
        var request = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", Integer.MAX_VALUE), new OrderItemRequestDTO("p1", 1)));

        assertThatThrownBy(() -> orderService.createOrder(request, "owner-sub", IDEMPOTENCY_KEY)).isInstanceOf(ArithmeticException.class);

        verifyNoInteractions(productClient, inventoryClient, orderRepository, outboxEventRepository, transactionManager);
    }

    private static OrderRequestDTO duplicatedItems() {
        return new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p2", 2),
                new OrderItemRequestDTO("p1", 1),
                new OrderItemRequestDTO("p1", 3)));
    }
}
