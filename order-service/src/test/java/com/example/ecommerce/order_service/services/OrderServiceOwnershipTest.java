package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.entities.Order;
import com.example.ecommerce.order_service.exceptions.OrderAccessDeniedException;
import com.example.ecommerce.order_service.repositories.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceOwnershipTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void customerListsOnlyOwnOrdersBySubject() {
        orderService.findAll(token("user-sub", "ROLE_CUSTOMER"));

        verify(orderRepository).findByUserID("user-sub");
    }

    @Test
    void adminListsAllOrders() {
        orderService.findAll(token("admin-sub", "ROLE_ADMIN"));

        verify(orderRepository).findAll();
    }

    @Test
    void customerCannotReadOrderOfAnotherSubject() {
        Order order = new Order();
        order.setUserID("owner-sub");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.findById(1L, token("intruder-sub", "ROLE_CUSTOMER")))
                .isInstanceOf(OrderAccessDeniedException.class);
    }

    private static JwtAuthenticationToken token(String subject, String authority) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)), subject);
    }
}
