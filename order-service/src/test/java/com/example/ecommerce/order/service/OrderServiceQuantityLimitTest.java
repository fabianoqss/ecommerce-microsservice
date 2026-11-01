package com.example.ecommerce.order.service;

import com.example.ecommerce.order.config.OrderCheckoutProperties;
import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.dto.client.inventory.InventoryItemDTO;
import com.example.ecommerce.order.dto.client.inventory.InventoryReserveRequestDTO;
import com.example.ecommerce.order.dto.client.product.ProductDTO;
import com.example.ecommerce.order.dto.request.OrderItemRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.exception.IdempotencyKeyReuseException;
import com.example.ecommerce.order.exception.OrderQuantityLimitExceededException;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.infra.client.ProductClient;
import com.example.ecommerce.order.infra.tracing.OutboxTraceContext;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceQuantityLimitTest {

    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("a4ee662f-e404-4cf5-afc0-d837d0beaf82");

    @Mock
    private ProductClient productClient;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private TransactionTemplate transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

    @Spy
    private ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private OutboxTraceContext outboxTraceContext = new OutboxTraceContext(Tracer.NOOP, Propagator.NOOP);

    @Spy
    private OrderCheckoutProperties checkoutProperties = new OrderCheckoutProperties(10000);

    @InjectMocks
    private OrderService orderService;

    @Test
    void aggregatedQuantityExactlyAtLimitConfirmsOrder() {
        stubSuccessfulCreation("p1");
        var request = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 6000), new OrderItemRequestDTO("p1", 4000)));

        var result = orderService.createOrder(request, "owner-sub", IDEMPOTENCY_KEY);

        assertThat(result.orderResponseDTO().status()).isEqualTo("CONFIRMED");
        assertThat(result.orderResponseDTO().totalValue()).isEqualByComparingTo("10000");
        verify(inventoryClient).reserve(any(UUID.class), eq(new InventoryReserveRequestDTO(
                List.of(new InventoryItemDTO("p1", 10000)))));
    }

    @Test
    void aggregatedQuantityAboveLimitRejectsBeforePersistingOrCallingClients() {
        var request = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 6000), new OrderItemRequestDTO("p1", 4001)));

        assertThatThrownBy(() -> orderService.createOrder(request, "owner-sub", IDEMPOTENCY_KEY))
                .isInstanceOf(OrderQuantityLimitExceededException.class)
                .hasMessageContaining("limite de 10000").hasMessageContaining("p1");

        verify(orderRepository, never()).saveAndFlush(any());
        verifyNoInteractions(productClient, inventoryClient, outboxEventRepository);
        assertThat(meterRegistry.get("orders.created").tag("result", "quantity_limit_exceeded").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void distinctProductsHaveIndependentQuantityLimits() {
        stubSuccessfulCreation("p1", "p2");
        var request = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 10000), new OrderItemRequestDTO("p2", 10000)));

        var result = orderService.createOrder(request, "owner-sub", IDEMPOTENCY_KEY);

        assertThat(result.orderResponseDTO().status()).isEqualTo("CONFIRMED");
        verify(inventoryClient).reserve(any(UUID.class), eq(new InventoryReserveRequestDTO(List.of(
                new InventoryItemDTO("p1", 10000), new InventoryItemDTO("p2", 10000)))));
    }

    @Test
    void singleLineAboveLimitIsRejectedByMaxConstraint() {
        var request = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 10001)));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("items[0].quantity");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Max.class);
            });
        }
    }

    @Test
    void configuredQuantityLimitRejectsAggregatedQuantityAboveIt() {
        var service = withQuantityLimit(10);
        var request = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 6), new OrderItemRequestDTO("p1", 5)));

        assertThatThrownBy(() -> service.createOrder(request, "owner-sub", IDEMPOTENCY_KEY))
                .isInstanceOf(OrderQuantityLimitExceededException.class)
                .hasMessageContaining("limite de 10");

        verify(orderRepository, never()).saveAndFlush(any());
        verifyNoInteractions(productClient, inventoryClient, outboxEventRepository);
    }

    @Test
    void configuredQuantityLimitAllowsExactlyTheLimit() {
        stubSuccessfulCreation("p1");
        var service = withQuantityLimit(10);
        var request = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 6), new OrderItemRequestDTO("p1", 4)));

        var result = service.createOrder(request, "owner-sub", IDEMPOTENCY_KEY);

        assertThat(result.orderResponseDTO().status()).isEqualTo("CONFIRMED");
        verify(inventoryClient).reserve(any(UUID.class), eq(new InventoryReserveRequestDTO(
                List.of(new InventoryItemDTO("p1", 10)))));
    }

    @Test
    void equivalentCartIsReplayedAfterQuantityLimitIsReduced() {
        Order existing = createOrderAtDefaultLimit();
        when(orderRepository.findByUserIDAndIdempotencyKey("owner-sub", IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));
        var equivalent = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 10000)));

        var result = withQuantityLimit(10).createOrder(equivalent, "owner-sub", IDEMPOTENCY_KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.orderResponseDTO().id()).isEqualTo(existing.getId());
        verify(orderRepository, times(2)).saveAndFlush(any());
        verify(productClient, times(1)).getProductById("p1");
        verify(inventoryClient, times(1)).reserve(any(), any());
        assertThat(meterRegistry.find("orders.created").tag("result", "quantity_limit_exceeded").counter()).isNull();
    }

    @Test
    void reusedKeyWithDifferentCartTakesPrecedenceOverQuantityLimit() {
        Order existing = createOrderAtDefaultLimit();
        when(orderRepository.findByUserIDAndIdempotencyKey("owner-sub", IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));
        var different = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 6000), new OrderItemRequestDTO("p1", 4001)));

        assertThatThrownBy(() -> withQuantityLimit(10).createOrder(different, "owner-sub", IDEMPOTENCY_KEY))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        verify(orderRepository, times(2)).saveAndFlush(any());
        verify(inventoryClient, times(1)).reserve(any(), any());
        assertThat(meterRegistry.find("orders.created").tag("result", "quantity_limit_exceeded").counter()).isNull();
    }

    private Order createOrderAtDefaultLimit() {
        stubSuccessfulCreation("p1");
        var request = new OrderRequestDTO(List.of(
                new OrderItemRequestDTO("p1", 6000), new OrderItemRequestDTO("p1", 4000)));
        orderService.createOrder(request, "owner-sub", IDEMPOTENCY_KEY);
        var captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private OrderService withQuantityLimit(int limit) {
        return new OrderService(orderRepository, productClient, inventoryClient, outboxEventRepository,
                objectMapper, meterRegistry, outboxTraceContext, transactionTemplate, new OrderCheckoutProperties(limit));
    }

    private void stubSuccessfulCreation(String... productIds) {
        for (String productId : productIds) {
            when(productClient.getProductById(productId)).thenReturn(new ProductDTO(productId, "Produto", BigDecimal.ONE));
        }
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(UUID.randomUUID());
            }
            return order;
        });
    }
}
