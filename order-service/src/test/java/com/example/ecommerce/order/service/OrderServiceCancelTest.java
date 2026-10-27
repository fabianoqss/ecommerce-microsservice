package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.entity.OrderItem;
import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.domain.enums.ReservationMode;
import com.example.ecommerce.order.dto.client.inventory.InventoryItemDTO;
import com.example.ecommerce.order.dto.event.OrderEventDTO;
import com.example.ecommerce.order.exception.OrderNotFoundException;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.infra.tracing.OutboxTraceContext;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceCancelTest {

    private static final UUID ORDER_ID = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryClient inventoryClient;

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

    @Test
    void ownerCancelsEnqueuesV3EventWithLedgerModeAndItemsWithoutHttpCall() throws Exception {
        Order order = confirmedOrder("owner-sub");
        when(orderRepository.findByIdAndUserID(ORDER_ID, "owner-sub")).thenReturn(Optional.of(order));

        var response = orderService.cancel(ORDER_ID, "Comprei errado", token("owner-sub", "ROLE_CUSTOMER"));

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(order.getCancelledBy()).isEqualTo("owner-sub");
        verify(orderRepository).saveAndFlush(order);
        verifyNoInteractions(inventoryClient);

        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outbox.capture());
        assertThat(outbox.getValue().getEventType()).isEqualTo("ORDER_CANCELLED");
        assertThat(outbox.getValue().getAggregateId()).isEqualTo(ORDER_ID);

        OrderEventDTO event = objectMapper.readValue(outbox.getValue().getPayload(), OrderEventDTO.class);
        assertThat(event.version()).isEqualTo(3);
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.status()).isEqualTo("CANCELLED");
        assertThat(event.reservationMode()).isEqualTo(ReservationMode.LEDGER);
        assertThat(event.items()).containsExactly(new InventoryItemDTO("p1", 2));

        assertThat(meterRegistry.counter("orders.cancelled", "actor", "owner").count()).isEqualTo(1.0);
    }

    @Test
    void cancellationAggregatesAndSortsItemsInV3EventAndPreservesLegacyMode() throws Exception {
        Order order = confirmedOrder("owner-sub");
        order.setReservationMode(ReservationMode.LEGACY);
        OrderItem p2 = new OrderItem();
        p2.setProductId("p2");
        p2.setQuantity(3);
        OrderItem duplicate = new OrderItem();
        duplicate.setProductId("p1");
        duplicate.setQuantity(4);
        order.setItems(List.of(p2, order.getItems().getFirst(), duplicate));
        when(orderRepository.findByIdAndUserID(ORDER_ID, "owner-sub")).thenReturn(Optional.of(order));

        orderService.cancel(ORDER_ID, null, token("owner-sub", "ROLE_CUSTOMER"));

        verifyNoInteractions(inventoryClient);
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outbox.capture());
        OrderEventDTO event = objectMapper.readValue(outbox.getValue().getPayload(), OrderEventDTO.class);
        assertThat(event.version()).isEqualTo(3);
        assertThat(event.reservationMode()).isEqualTo(ReservationMode.LEGACY);
        assertThat(event.items()).containsExactly(
                new InventoryItemDTO("p1", 6), new InventoryItemDTO("p2", 3));
        assertThat(order.getItems()).hasSize(3);
    }

    @Test
    void adminCanCancelOrderWithNullOwner() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(confirmedOrder(null)));

        var response = orderService.cancel(ORDER_ID, null, token("admin-sub", "ROLE_ADMIN"));

        assertThat(response.status()).isEqualTo("CANCELLED");
        verifyNoInteractions(inventoryClient);
        assertThat(meterRegistry.counter("orders.cancelled", "actor", "admin").count()).isEqualTo(1.0);
    }

    @Test
    void adminCancelsOrderOfAnotherUser() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(confirmedOrder("owner-sub")));

        orderService.cancel(ORDER_ID, null, token("admin-sub", "ROLE_ADMIN"));

        verifyNoInteractions(inventoryClient);
        verify(orderRepository, never()).findByIdAndUserID(any(), any());
        verify(outboxEventRepository).save(any());
        assertThat(meterRegistry.counter("orders.cancelled", "actor", "admin").count()).isEqualTo(1.0);
    }

    @Test
    void customerGetsNotFoundForOrderOfAnotherUser() {
        when(orderRepository.findByIdAndUserID(ORDER_ID, "intruder-sub")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancel(ORDER_ID, null, token("intruder-sub", "ROLE_CUSTOMER")))
                .isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(inventoryClient);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void cancellingTwiceDoesNotEnqueueEventAgain() {
        Order order = confirmedOrder("owner-sub");
        order.cancel("owner-sub", null, LocalDateTime.now());
        when(orderRepository.findByIdAndUserID(ORDER_ID, "owner-sub")).thenReturn(Optional.of(order));

        var response = orderService.cancel(ORDER_ID, null, token("owner-sub", "ROLE_CUSTOMER"));

        assertThat(response.status()).isEqualTo("CANCELLED");
        verifyNoInteractions(inventoryClient);
        verify(outboxEventRepository, never()).save(any());
    }

    private static Order confirmedOrder(String ownerSub) {
        OrderItem item = new OrderItem();
        item.setProductId("p1");
        item.setProductName("Teclado");
        item.setQuantity(2);
        item.setPrice(new BigDecimal("10.00"));

        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserID(ownerSub);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setReservationMode(ReservationMode.LEDGER);
        order.setOrderTime(LocalDateTime.of(2026, 10, 20, 10, 0));
        order.setTotalValue(new BigDecimal("20.00"));
        order.setItems(List.of(item));
        return order;
    }

    private static JwtAuthenticationToken token(String subject, String authority) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)), subject);
    }
}
