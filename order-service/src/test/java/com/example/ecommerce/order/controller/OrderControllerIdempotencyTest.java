package com.example.ecommerce.order.controller;

import com.example.ecommerce.order.dto.request.OrderItemRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.dto.response.CreateOrderResult;
import com.example.ecommerce.order.dto.response.OrderItemResponseDTO;
import com.example.ecommerce.order.dto.response.OrderResponseDTO;
import com.example.ecommerce.order.exception.IdempotencyKeyReuseException;
import com.example.ecommerce.order.exception.OrderInProgressException;
import com.example.ecommerce.order.infra.security.SecurityConfig;
import com.example.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerIdempotencyTest {

    private static final String USER_ID = "owner-sub";
    private static final UUID ORDER_ID = UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("a4ee662f-e404-4cf5-afc0-d837d0beaf82");
    private static final OrderRequestDTO ORDER = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 1)));
    private static final String ORDER_BODY = """
            {"items":[{"productId":"p1","quantity":1}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void missingIdempotencyKeyReturnsBadRequestProblemDetail() throws Exception {
        mvc.perform(authenticatedOrder())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").value("/api/order"));

        verifyNoInteractions(orderService);
    }

    @Test
    void invalidIdempotencyKeyReturnsBadRequestProblemDetail() throws Exception {
        mvc.perform(authenticatedOrder().header("Idempotency-Key", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Parâmetro inválido: Idempotency-Key"))
                .andExpect(jsonPath("$.instance").value("/api/order"));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createdResponseIncludesIdempotentReplayedHeaderAndOrderBody(boolean replayed) throws Exception {
        var response = new OrderResponseDTO(ORDER_ID, "CONFIRMED", BigDecimal.TEN,
                LocalDateTime.of(2026, 10, 27, 10, 0),
                List.of(new OrderItemResponseDTO("p1", "Teclado", 1, BigDecimal.TEN)));
        when(orderService.createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(new CreateOrderResult(response, replayed));

        mvc.perform(authenticatedOrder().header("Idempotency-Key", IDEMPOTENCY_KEY.toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", Boolean.toString(replayed)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalValue").value(10))
                .andExpect(jsonPath("$.items[0].productId").value("p1"))
                .andExpect(jsonPath("$.items[0].quantity").value(1));

        verify(orderService).createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY);
    }

    @Test
    void orderInProgressReturnsConflictProblemDetailWithOrderId() throws Exception {
        when(orderService.createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY))
                .thenThrow(new OrderInProgressException(ORDER_ID));

        mvc.perform(authenticatedOrder().header("Idempotency-Key", IDEMPOTENCY_KEY.toString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()));

        verify(orderService).createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY);
    }

    @Test
    void idempotencyKeyReuseReturnsUnprocessableContentProblemDetail() throws Exception {
        when(orderService.createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY))
                .thenThrow(new IdempotencyKeyReuseException("Key already used with another cart"));

        mvc.perform(authenticatedOrder().header("Idempotency-Key", IDEMPOTENCY_KEY.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Key already used with another cart"));

        verify(orderService).createOrder(ORDER, USER_ID, IDEMPOTENCY_KEY);
    }

    private static MockHttpServletRequestBuilder authenticatedOrder() {
        return post("/api/order")
                .with(jwt().jwt(j -> j.subject(USER_ID))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORDER_BODY);
    }
}
