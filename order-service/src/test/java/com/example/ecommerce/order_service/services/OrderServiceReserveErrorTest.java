package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.client.InventoryClient;
import com.example.ecommerce.order_service.client.ProductClient;
import com.example.ecommerce.order_service.dtos.request.OrderItemRequestDTO;
import com.example.ecommerce.order_service.dtos.request.OrderRequestDTO;
import com.example.ecommerce.order_service.dtos.response.ProductDTO;
import com.example.ecommerce.order_service.exceptions.InsufficientStockException;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceReserveErrorTest {

    private static final Request RESERVE = Request.create(Request.HttpMethod.POST, "/api/inventory/reserve",
            Map.of(), null, StandardCharsets.UTF_8, null);
    private static final OrderRequestDTO ORDER = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 1)));

    @Mock
    private ProductClient productClient;

    @Mock
    private InventoryClient inventoryClient;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void productExists() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
    }

    @Test
    void conflictMeansInsufficientStock() {
        doThrow(new FeignException.Conflict("conflict", RESERVE, null, null)).when(inventoryClient).reserve(any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void reserveForbiddenIsNotReportedAsInsufficientStock() {
        doThrow(new FeignException.Forbidden("forbidden", RESERVE, null, null)).when(inventoryClient).reserve(any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(FeignException.Forbidden.class);
    }

    @Test
    void reserveUnauthorizedIsNotReportedAsInsufficientStock() {
        doThrow(new FeignException.Unauthorized("unauthorized", RESERVE, null, null)).when(inventoryClient).reserve(any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(FeignException.Unauthorized.class);
    }
}
