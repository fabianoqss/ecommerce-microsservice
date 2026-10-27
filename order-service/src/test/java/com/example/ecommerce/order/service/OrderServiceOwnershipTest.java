package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.exception.OrderNotFoundException;
import com.example.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceOwnershipTest {

    private static final UUID ORDER_ID = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void customerListsOnlyOwnConfirmedOrdersBySubject() {
        when(orderRepository.findByUserIDAndConfirmedAtIsNotNull(eq("user-sub"), any(Pageable.class)))
                .thenReturn(Page.empty());

        orderService.findAll(0, 20, token("user-sub", "ROLE_CUSTOMER"));

        verify(orderRepository).findByUserIDAndConfirmedAtIsNotNull(eq("user-sub"), any(Pageable.class));
        verify(orderRepository, never()).findAll(any(Pageable.class));
        verify(orderRepository, never()).findByUserID(any(), any(Pageable.class));
    }

    @Test
    void adminListsAllOrders() {
        when(orderRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        orderService.findAll(0, 20, token("admin-sub", "ROLE_ADMIN"));

        verify(orderRepository, never()).findByUserIDAndConfirmedAtIsNotNull(any(), any(Pageable.class));
        verify(orderRepository, never()).findByUserID(any(), any(Pageable.class));
    }

    @Test
    void listIsPagedNewestFirstWithRequestedPageAndSize() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(orderRepository.findByUserIDAndConfirmedAtIsNotNull(eq("user-sub"), pageable.capture()))
                .thenReturn(Page.empty());

        orderService.findAll(2, 10, token("user-sub", "ROLE_CUSTOMER"));

        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "orderTime", "id"));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "EXPIRED", "RECONCILIATION_FAILED"})
    void customerCanGetOwnUnconfirmedOrderById(OrderStatus status) {
        Order pending = order("owner-sub");
        pending.setStatus(status);
        pending.setConfirmedAt(null);
        when(orderRepository.findByIdAndUserID(ORDER_ID, "owner-sub")).thenReturn(Optional.of(pending));

        var dto = orderService.findById(ORDER_ID, token("owner-sub", "ROLE_CUSTOMER"));

        assertThat(dto.id()).isEqualTo(ORDER_ID);
        assertThat(dto.status()).isEqualTo(status.name());
    }

    @Test
    void customerGetsNotFoundForOrderOfAnotherSubject() {
        when(orderRepository.findByIdAndUserID(ORDER_ID, "intruder-sub")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(ORDER_ID, token("intruder-sub", "ROLE_CUSTOMER")))
                .isInstanceOf(OrderNotFoundException.class);
        verify(orderRepository, never()).findById(any(UUID.class));
    }

    @Test
    void adminReadsOrderOfAnySubject() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order("owner-sub")));

        assertThat(orderService.findById(ORDER_ID, token("admin-sub", "ROLE_ADMIN")).id()).isEqualTo(ORDER_ID);
        verify(orderRepository, never()).findByIdAndUserID(any(UUID.class), any());
    }

    private static Order order(String ownerSub) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserID(ownerSub);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setItems(List.of());
        return order;
    }

    private static JwtAuthenticationToken token(String subject, String authority) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)), subject);
    }
}
