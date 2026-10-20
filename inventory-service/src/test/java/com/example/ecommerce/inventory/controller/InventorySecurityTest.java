package com.example.ecommerce.inventory.controller;

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
import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventorySecurityTest {

    private static final String RESERVE_BODY = """
            {"items":[{"skuCode":"SKU-1","quantity":1}]}
            """;
    private static final String INSERT_BODY = """
            {"skuCode":"SKU-1","quantity":10}
            """;
    private static final String UPDATE_BODY = """
            {"quantity":5}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ResultMatcher allowed() {
        return result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void anonymousCanCheckStock() throws Exception {
        mvc.perform(get("/api/inventory").param("skuCode", "SKU-1")).andExpect(allowed());
    }

    @Test
    void reserveAndReleaseRequireServiceScope() throws Exception {
        var service = jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));
        mvc.perform(post("/api/inventory/reserve").with(service).contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(allowed());
        mvc.perform(post("/api/inventory/release").with(service).contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(allowed());
    }

    @Test
    void customerAndAdminCannotReserve() throws Exception {
        for (String role : new String[]{"ROLE_CUSTOMER", "ROLE_ADMIN"}) {
            var user = jwt().authorities(new SimpleGrantedAuthority(role));
            mvc.perform(post("/api/inventory/reserve").with(user).contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/inventory/release").with(user).contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void anonymousCannotReserve() throws Exception {
        mvc.perform(post("/api/inventory/reserve").contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminManagesItems() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var customer = jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        var service = jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));

        mvc.perform(post("/api/inventory").with(admin).contentType(MediaType.APPLICATION_JSON).content(INSERT_BODY))
                .andExpect(allowed());
        mvc.perform(put("/api/inventory/1").with(admin).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(allowed());

        mvc.perform(post("/api/inventory").with(customer).contentType(MediaType.APPLICATION_JSON).content(INSERT_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/inventory/1").with(service).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isForbidden());
    }
}
