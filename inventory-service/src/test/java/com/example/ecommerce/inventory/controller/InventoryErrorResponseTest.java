package com.example.ecommerce.inventory.controller;

import com.example.ecommerce.inventory.dto.request.InventoryUpdateDTO;
import com.example.ecommerce.inventory.exception.InsufficientStockException;
import com.example.ecommerce.inventory.exception.InventoryNotFoundException;
import com.example.ecommerce.inventory.infra.security.SecurityConfig;
import com.example.ecommerce.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventoryErrorResponseTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

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
    void insufficientStockReturnsConflictProblemDetail() throws Exception {
        doThrow(new InsufficientStockException("SKU-1")).when(inventoryService).reserve(any());

        mvc.perform(post("/api/inventory/reserve")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuCode\":\"SKU-1\",\"quantity\":2}]}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Estoque insuficiente ou SKU inexistente: SKU-1"));
    }

    @Test
    void invalidInventoryReturnsProblemDetailWithValidationErrors() throws Exception {
        mvc.perform(post("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"skuCode\":\"SKU-1\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Payload inválido"))
                .andExpect(jsonPath("$.errors[0]").value("quantity: Quantidade não pode ser negativa"));
    }

    @Test
    void unexpectedFailureDoesNotLeakInternalDetails() throws Exception {
        when(inventoryService.isInStock(List.of("SKU-1")))
                .thenThrow(new IllegalStateException("database password"));

        mvc.perform(get("/api/inventory").param("skuCode", "SKU-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Erro interno inesperado"));
    }
}
