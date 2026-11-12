package com.example.ecommerce.product.controller;

import com.example.ecommerce.product.dto.request.ProductRequestDTO;
import com.example.ecommerce.product.dto.response.ProductResponseDTO;
import com.example.ecommerce.product.infra.security.SecurityConfig;
import com.example.ecommerce.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductSecurityTest {

    private static final String VALID_PRODUCT = """
            {"name":"Keyboard","description":"RGB mechanical keyboard","price":349.90,"category":"Peripherals"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ResultMatcher allowed() {
        return result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void anonymousCanReadProducts() throws Exception {
        when(productService.findByID("abc")).thenReturn(created(new ProductRequestDTO(
                "Keyboard", "RGB mechanical keyboard", new BigDecimal("349.90"), "Peripherals", null)));
        mvc.perform(get("/api/product")).andExpect(allowed());
        mvc.perform(get("/api/product/abc")).andExpect(allowed());
    }

    @Test
    void listRejectsInvalidPagination() throws Exception {
        mvc.perform(get("/api/product").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/product").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/product").param("size", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/product").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/product").param("page", "abc")).andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @Test
    void anonymousCannotWrite() throws Exception {
        mvc.perform(post("/api/product").contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/product/abc")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotWrite() throws Exception {
        var customer = jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        mvc.perform(post("/api/product").with(customer).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/product/abc").with(customer).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/product/abc").with(customer)).andExpect(status().isForbidden());
    }

    @Test
    void adminCanWrite() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        when(productService.insert(any())).thenReturn(
                new ProductResponseDTO("abc", "Keyboard", "RGB mechanical keyboard", new BigDecimal("349.90"), "Peripherals", null, 0L));
        mvc.perform(post("/api/product").with(admin).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isCreated());
        when(productService.update(eq("abc"), eq(0L), any())).thenAnswer(invocation -> created(invocation.getArgument(2)));
        mvc.perform(put("/api/product/abc").with(admin).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/product/abc").with(admin).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNoContent());
    }

    @Test
    void adminCanCreateProductAtNameAndPriceLimits() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        when(productService.insert(any())).thenAnswer(invocation -> created(invocation.getArgument(0)));
        String product = VALID_PRODUCT.replace("Keyboard\"", "a".repeat(255) + "\"")
                .replace("349.90", "99999999999999999.99");

        mvc.perform(post("/api/product").with(admin).contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isCreated());
    }

    @Test
    void adminCannotCreateProductWithNameLongerThan255Characters() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        when(productService.insert(any())).thenAnswer(invocation -> created(invocation.getArgument(0)));
        String product = VALID_PRODUCT.replace("Keyboard\"", "a".repeat(256) + "\"");

        mvc.perform(post("/api/product").with(admin).contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists());
    }

    @Test
    void adminCannotCreateProductWithMoreThanTwoDecimalPlaces() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        when(productService.insert(any())).thenAnswer(invocation -> created(invocation.getArgument(0)));
        String product = VALID_PRODUCT.replace("349.90", "10.005");

        mvc.perform(post("/api/product").with(admin).contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.price").exists());
    }

    @Test
    void adminCannotCreateProductWithoutPrice() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        when(productService.insert(any())).thenAnswer(invocation -> created(invocation.getArgument(0)));
        String product = VALID_PRODUCT.replace("\"price\":349.90,", "");

        mvc.perform(post("/api/product").with(admin).contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.price").exists());
    }

    @Test
    void adminCannotCreateProductWithMoreThan17IntegerDigitsInPrice() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        when(productService.insert(any())).thenAnswer(invocation -> created(invocation.getArgument(0)));
        String product = VALID_PRODUCT.replace("349.90", "100000000000000000.00");

        mvc.perform(post("/api/product").with(admin).contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.price").exists());
    }

    @Test
    void serviceScopeIsNotAdmin() throws Exception {
        var service = jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));
        mvc.perform(delete("/api/product/abc").with(service)).andExpect(status().isForbidden());
    }

    @Test
    void invalidBearerOnPublicRouteIs401() throws Exception {
        when(jwtDecoder.decode("garbage")).thenThrow(new BadJwtException("invalid"));
        mvc.perform(get("/api/product").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsPublicAndOtherActuatorEndpointsAreDenied() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(allowed());
        mvc.perform(get("/actuator/env").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    private static ProductResponseDTO created(ProductRequestDTO request) {
        return new ProductResponseDTO("abc", request.name(), request.description(), request.price(),
                request.category(), request.imageUrl(), 0L);
    }
}
