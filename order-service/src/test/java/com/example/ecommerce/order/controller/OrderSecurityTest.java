package com.example.ecommerce.order.controller;

import com.example.ecommerce.order.dto.response.CreateOrderResult;
import com.example.ecommerce.order.exception.InvalidProductDataException;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderSecurityTest {

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
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/order")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createOrderUsesTokenSubjectAsUserId() throws Exception {
        when(orderService.createOrder(any(), any(), any(UUID.class)))
                .thenReturn(new CreateOrderResult(null, false));

        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.subject("4622f3de-user-sub"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(), eq("4622f3de-user-sub"), any(UUID.class));
    }

    @Test
    void createRejectsInvalidProductId() throws Exception {
        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"x/..","quantity":1}]}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void createRejectsMoreThan50Items() throws Exception {
        String items = String.join(",", java.util.Collections.nCopies(51, "{\"productId\":\"p1\",\"quantity\":1}"));

        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void createAccepts50ItemsAtQuantityLimit() throws Exception {
        when(orderService.createOrder(any(), any(), any(UUID.class)))
                .thenReturn(new CreateOrderResult(null, false));

        String items = String.join(",", java.util.Collections.nCopies(50, "{\"productId\":\"p1\",\"quantity\":10000}"));

        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"))
                .andExpect(status().isCreated());
        verify(orderService).createOrder(any(), any(), any(UUID.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10001", "null"})
    void createRejectsInvalidQuantity(String quantity) throws Exception {
        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"p1\",\"quantity\":" + quantity + "}]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"items\":null}", "{\"items\":[]}", "{\"items\":[null]}"})
    void createRejectsMissingOrEmptyItems(String body) throws Exception {
        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void invalidProductDataReturnsBadGatewayProblemDetail() throws Exception {
        when(orderService.createOrder(any(), any(), any(UUID.class))).thenThrow(new InvalidProductDataException("p1"));

        mvc.perform(post("/api/order")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("product-service")));
    }

    @Test
    void listRejectsInvalidPaginationWithBadRequest() throws Exception {
        var customer = jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));

        mvc.perform(get("/api/order").param("size", "101").with(customer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/order").param("size", "0").with(customer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/order").param("page", "-1").with(customer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/order").param("page", "abc").with(customer)).andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void cancelRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/order/019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b/cancel"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(orderService);
    }

    @Test
    void cancelRejectsTooLongReason() throws Exception {
        mvc.perform(post("/api/order/019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b/cancel")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void cancelWithoutBodyUsesTokenAuthentication() throws Exception {
        mvc.perform(post("/api/order/019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b/cancel")
                        .with(jwt().jwt(j -> j.subject("owner-sub")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());

        verify(orderService).cancel(eq(java.util.UUID.fromString("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b")), eq(null), any());
    }

    @Test
    void getByIdRejectsNonUuidWithBadRequest() throws Exception {
        mvc.perform(get("/api/order/123").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void listUsesDefaultPagination() throws Exception {
        mvc.perform(get("/api/order").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());

        verify(orderService).findAll(eq(0), eq(20), any());
    }
}
