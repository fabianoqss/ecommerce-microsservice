package com.example.ecommerce.inventory.controller;

import com.example.ecommerce.inventory.dto.request.InventoryUpdateDTO;
import com.example.ecommerce.inventory.exception.InsufficientStockException;
import com.example.ecommerce.inventory.exception.InventoryNotFoundException;
import com.example.ecommerce.inventory.exception.ReservationConflictException;
import com.example.ecommerce.inventory.exception.ReservationGoneException;
import com.example.ecommerce.inventory.exception.ReservationNotFoundException;
import com.example.ecommerce.inventory.infra.security.SecurityConfig;
import com.example.ecommerce.inventory.service.InventoryService;
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventoryErrorResponseTest {

    private static final UUID RESERVATION_ID = UUID.fromString("0195f000-0000-7000-8000-000000000002");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void missingSkuCodeReturnsBadRequestProblemDetail() throws Exception {
        mvc.perform(get("/api/inventory"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Required parameter 'skuCode' is not present."));
    }

    @Test
    void unsupportedMethodReturnsMethodNotAllowedProblemDetail() throws Exception {
        mvc.perform(get("/api/inventory/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "PUT"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void missingResourceReturnsNotFoundProblemDetail() throws Exception {
        mvc.perform(get("/api/inventory/unknown/path"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void unsupportedMediaTypeReturnsProblemDetail() throws Exception {
        mvc.perform(post("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void unacceptableMediaTypeReturnsProblemDetail() throws Exception {
        mvc.perform(get("/api/inventory").param("skuCode", "SKU-1").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(406));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x/..", "SKU 1", "sku;drop", "\u00e7", "a12345678901234567890123456789012345678901234567890123456789012345"})
    void insertRejectsInvalidSkuCode(String skuCode) throws Exception {
        mvc.perform(post("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuCode\":\"" + skuCode + "\",\"quantity\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(inventoryService);
    }

    @Test
    void reserveRejectsInvalidSkuCode() throws Exception {
        mvc.perform(put("/api/inventory/reservations/" + RESERVATION_ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuCode\":\"x/..\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(inventoryService);
    }

    @Test
    void malformedPayloadPreservesBadRequestContract() throws Exception {
        mvc.perform(post("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Payload ou parâmetro inválido"));
    }

    @Test
    void invalidParameterPreservesBadRequestContract() throws Exception {
        mvc.perform(put("/api/inventory/invalid")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Payload ou parâmetro inválido"));
    }

    @Test
    void missingInventoryReturnsProblemDetail() throws Exception {
        when(inventoryService.update(99L, new InventoryUpdateDTO(1)))
                .thenThrow(new InventoryNotFoundException(99L));

        mvc.perform(put("/api/inventory/99")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Item de estoque não encontrado com id: 99"))
                .andExpect(jsonPath("$.instance").value("/api/inventory/99"));
    }

    @Test
    void missingReservationReturnsNotFoundProblemDetail() throws Exception {
        when(inventoryService.getReservation(RESERVATION_ID))
                .thenThrow(new ReservationNotFoundException(RESERVATION_ID));

        mvc.perform(get("/api/inventory/reservations/" + RESERVATION_ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Reserva de estoque não encontrada: " + RESERVATION_ID));
    }

    @Test
    void insufficientStockReturnsConflictProblemDetail() throws Exception {
        when(inventoryService.reserve(eq(RESERVATION_ID), any()))
                .thenThrow(new InsufficientStockException("SKU-1"));

        mvc.perform(put("/api/inventory/reservations/" + RESERVATION_ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuCode\":\"SKU-1\",\"quantity\":2}]}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Estoque insuficiente ou SKU inexistente: SKU-1"));
    }

    @Test
    void reservationConflictItemsReturns422ProblemDetail() throws Exception {
        when(inventoryService.reserve(eq(RESERVATION_ID), any()))
                .thenThrow(new ReservationConflictException(RESERVATION_ID));

        mvc.perform(put("/api/inventory/reservations/" + RESERVATION_ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuCode\":\"SKU-1\",\"quantity\":2}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Reserva já existe com itens divergentes: " + RESERVATION_ID));
    }

    @Test
    void reservationAlreadyReleasedReturns410GoneProblemDetail() throws Exception {
        when(inventoryService.reserve(eq(RESERVATION_ID), any()))
                .thenThrow(new ReservationGoneException(RESERVATION_ID));

        mvc.perform(put("/api/inventory/reservations/" + RESERVATION_ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuCode\":\"SKU-1\",\"quantity\":2}]}"))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.detail").value("Reserva já foi liberada ou cancelada: " + RESERVATION_ID));
    }

    @Test
    void reservationValidationRejectsInvalidQuantityBlankSkuAndTooManyItems() throws Exception {
        var service = jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));

        mvc.perform(put("/api/inventory/reservations/" + RESERVATION_ID)
                        .with(service)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuCode\":\"SKU-1\",\"quantity\":10001}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        mvc.perform(put("/api/inventory/reservations/" + RESERVATION_ID)
                        .with(service)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuCode\":\"   \",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        String fiftyOneItems = IntStream.rangeClosed(1, 51)
                .mapToObj(i -> "{\"skuCode\":\"SKU-" + i + "\",\"quantity\":1}")
                .collect(Collectors.joining(",", "{\"items\":[", "]}"));

        mvc.perform(put("/api/inventory/reservations/" + RESERVATION_ID)
                        .with(service)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fiftyOneItems))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void invalidInventoryReturnsProblemDetailWithValidationErrors() throws Exception {
        mvc.perform(post("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"skuCode\":\"SKU-1\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Payload inválido"))
                .andExpect(jsonPath("$.errors").value(contains("quantity: Quantidade não pode ser negativa")));
    }

    @Test
    void unexpectedFailureDoesNotLeakInternalDetails() throws Exception {
        when(inventoryService.isInStock(List.of("SKU-1")))
                .thenThrow(new IllegalStateException("database password"));

        mvc.perform(get("/api/inventory").param("skuCode", "SKU-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Erro interno inesperado"));
    }
}
