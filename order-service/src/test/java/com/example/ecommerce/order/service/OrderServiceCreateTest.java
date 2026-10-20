package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.Order;
import com.example.ecommerce.order.domain.OutboxEvent;
import com.example.ecommerce.order.dto.request.InventoryItemDTO;
import com.example.ecommerce.order.dto.request.InventoryReserveRequestDTO;
import com.example.ecommerce.order.dto.request.OrderItemRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.dto.response.ProductDTO;
import com.example.ecommerce.order.exception.InvalidProductDataException;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.infra.client.ProductClient;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceCreateTest {

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

    @InjectMocks
    private OrderService orderService;

    @Test
    void nullPriceFailsBeforeReserve() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", null));

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "owner-sub"))
                .isInstanceOf(InvalidProductDataException.class)
                .hasMessageContaining("product-service").hasMessageContaining("p1");

        verifyNoInteractions(inventoryClient, orderRepository, outboxEventRepository, transactionManager);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void invalidNameFailsBeforeReserve(String name) {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", name, BigDecimal.TEN));

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "owner-sub"))
                .isInstanceOf(InvalidProductDataException.class)
                .hasMessageContaining("product-service").hasMessageContaining("p1");

        verifyNoInteractions(inventoryClient, orderRepository, outboxEventRepository, transactionManager);
    }

    @Test
    void invalidLaterProductFailsBeforeReserve() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(productClient.getProductById("p2")).thenReturn(new ProductDTO("p2", "Mouse", null));
        var request = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 1), new OrderItemRequestDTO("p2", 2)));

        assertThatThrownBy(() -> orderService.createOrder(request, "owner-sub"))
                .isInstanceOf(InvalidProductDataException.class)
                .hasMessageContaining("product-service").hasMessageContaining("p2");

        verifyNoInteractions(inventoryClient, orderRepository, outboxEventRepository, transactionManager);
    }

    @Test
    void flushFailureRollsBackBeforeReleasingExactlyOnce() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        var transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        var failure = new DataIntegrityViolationException("Falha no flush");
        when(orderRepository.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "owner-sub")).isSameAs(failure);

        var calls = inOrder(inventoryClient, transactionManager, orderRepository);
        calls.verify(inventoryClient).reserve(new InventoryReserveRequestDTO(List.of(new InventoryItemDTO("p1", 1))));
        calls.verify(transactionManager).getTransaction(any());
        calls.verify(orderRepository).saveAndFlush(any());
        calls.verify(transactionManager).rollback(transaction);
        calls.verify(inventoryClient).release(new InventoryReserveRequestDTO(List.of(new InventoryItemDTO("p1", 1))));
        verifyNoMoreInteractions(inventoryClient);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void commitFailureReleasesAggregatedStockExactlyOnce() {
        stubProductsAndPersistence();
        var transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        var failure = new TransactionSystemException("Falha no commit");
        doThrow(failure).when(transactionManager).commit(transaction);

        assertThatThrownBy(() -> orderService.createOrder(duplicatedItems(), "owner-sub")).isSameAs(failure);

        var expected = new InventoryReserveRequestDTO(List.of(new InventoryItemDTO("p1", 4), new InventoryItemDTO("p2", 2)));
        var calls = inOrder(inventoryClient, transactionManager, orderRepository, outboxEventRepository);
        calls.verify(inventoryClient).reserve(expected);
        calls.verify(transactionManager).getTransaction(any());
        calls.verify(orderRepository).saveAndFlush(any());
        calls.verify(outboxEventRepository).save(any());
        calls.verify(transactionManager).commit(transaction);
        calls.verify(inventoryClient).release(expected);
        verifyNoMoreInteractions(inventoryClient);
    }

    @Test
    void reserveAggregatesAndSortsWithoutChangingOrderItems() {
        stubProductsAndPersistence();

        var response = orderService.createOrder(duplicatedItems(), "owner-sub");

        var calls = inOrder(productClient, inventoryClient, transactionManager, orderRepository, outboxEventRepository);
        calls.verify(productClient).getProductById("p2");
        calls.verify(productClient).getProductById("p1");
        calls.verify(inventoryClient).reserve(new InventoryReserveRequestDTO(List.of(
                new InventoryItemDTO("p1", 4), new InventoryItemDTO("p2", 2))));
        calls.verify(transactionManager).getTransaction(any());
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        calls.verify(orderRepository).saveAndFlush(saved.capture());
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        calls.verify(outboxEventRepository).save(outbox.capture());
        calls.verify(transactionManager).commit(any());
        verify(inventoryClient, never()).release(any());
        verifyNoMoreInteractions(productClient);

        assertThat(saved.getValue().getItems()).extracting(item -> item.getProductId()).containsExactly("p2", "p1", "p1");
        assertThat(saved.getValue().getItems()).extracting(item -> item.getQuantity()).containsExactly(2, 1, 3);
        assertThat(response.id()).isEqualTo(ORDER_ID);
        assertThat(response.totalValue()).isEqualByComparingTo("80");
        assertThat(response.items()).hasSize(3);
        assertThat(outbox.getValue().getAggregateId()).isEqualTo(ORDER_ID);
        assertThat(outbox.getValue().getEventType()).isEqualTo("ORDER_CREATED");
    }

    @Test
    void responseIsMappedBeforeTransactionCloses() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        var committed = new AtomicBoolean();
        doAnswer(invocation -> {
            committed.set(true);
            return null;
        }).when(transactionManager).commit(any());
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(ORDER_ID);
            var items = order.getItems();
            var persisted = spy(order);
            // Simula uma associação que não pode ser acessada após fechar a sessão.
            doAnswer(call -> {
                if (committed.get()) {
                    throw new org.hibernate.LazyInitializationException("Sessão encerrada");
                }
                return items;
            }).when(persisted).getItems();
            return persisted;
        });

        var response = orderService.createOrder(ORDER, "owner-sub");

        assertThat(committed).isTrue();
        assertThat(response.id()).isEqualTo(ORDER_ID);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo("p1");
            assertThat(item.quantity()).isEqualTo(1);
        });
    }

    @Test
    void quantityOverflowFailsBeforeReserve() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        var request = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", Integer.MAX_VALUE), new OrderItemRequestDTO("p1", 1)));

        assertThatThrownBy(() -> orderService.createOrder(request, "owner-sub")).isInstanceOf(ArithmeticException.class);

        verifyNoInteractions(inventoryClient, orderRepository, outboxEventRepository, transactionManager);
    }

    private void stubProductsAndPersistence() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
        when(productClient.getProductById("p2")).thenReturn(new ProductDTO("p2", "Mouse", new BigDecimal("20")));
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(ORDER_ID);
            return order;
        });
    }

    private static OrderRequestDTO duplicatedItems() {
        return new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p2", 2),
                new OrderItemRequestDTO("p1", 1),
                new OrderItemRequestDTO("p1", 3)));
    }
}
