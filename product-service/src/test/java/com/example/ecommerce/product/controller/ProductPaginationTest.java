package com.example.ecommerce.product.controller;

import com.example.ecommerce.product.domain.entity.Product;
import com.example.ecommerce.product.infra.security.SecurityConfig;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, ProductService.class})
class ProductPaginationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void defaultPageIsBoundedAndUsesStableResponse() throws Exception {
        var pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty(pageable));

        mvc.perform(get("/api/product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.pageable").doesNotExist());
        verify(repository).findAll(pageable);
    }

    @Test
    void requestedPageMapsProductsAndPreservesTotals() throws Exception {
        var product = new Product();
        product.setId("p1");
        product.setName("Teclado");
        product.setPrice(new BigDecimal("10.00"));
        var pageable = PageRequest.of(1, 20, Sort.by("id"));
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(product), pageable, 45));

        mvc.perform(get("/api/product").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("p1"))
                .andExpect(jsonPath("$.content[0].name").value("Teclado"))
                .andExpect(jsonPath("$.content[0].price").value(10.00))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(45))
                .andExpect(jsonPath("$.totalPages").value(3));
        verify(repository).findAll(pageable);
    }

    @Test
    void acceptsMaximumPageSize() throws Exception {
        var pageable = PageRequest.of(0, 100, Sort.by("id"));
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty(pageable));

        mvc.perform(get("/api/product").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        verify(repository).findAll(pageable);
    }
}
