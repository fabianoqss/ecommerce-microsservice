package com.example.ecommerce.product.controller;

import com.example.ecommerce.product.dto.request.ProductRequestDTO;
import com.example.ecommerce.product.dto.response.ProductResponseDTO;
import com.example.ecommerce.product.exception.ProductVersionConflictException;
import com.example.ecommerce.product.infra.security.SecurityConfig;
import com.example.ecommerce.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductConditionalRequestTest {

    private static final String VALID_PRODUCT = """
            {"name":"Teclado","description":"Teclado mecânico RGB","price":349.90,"category":"Periféricos"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void getExposesVersionAsStrongEtag() throws Exception {
        when(productService.findByID("abc")).thenReturn(product(3L));

        mvc.perform(get("/api/product/abc"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void updateWithMatchingEtagReturnsNewEtag() throws Exception {
        when(productService.update(eq("abc"), eq(3L), any(ProductRequestDTO.class))).thenReturn(product(4L));

        mvc.perform(put("/api/product/abc").with(admin()).header(HttpHeaders.IF_MATCH, "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "*"})
    void updateWithoutConcreteEtagReturns428(String ifMatch) throws Exception {
        mvc.perform(put("/api/product/abc").with(admin()).header(HttpHeaders.IF_MATCH, ifMatch)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Precondition Required"));
        verifyNoInteractions(productService);
    }

    @Test
    void updateWithoutIfMatchHeaderReturns428() throws Exception {
        mvc.perform(put("/api/product/abc").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isPreconditionRequired());
        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "W/\"3\"", "\"abc\"", "\"1\", \"2\"", "\"-1\""})
    void updateWithMalformedEtagReturns400(String ifMatch) throws Exception {
        mvc.perform(put("/api/product/abc").with(admin()).header(HttpHeaders.IF_MATCH, ifMatch)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @Test
    void updateWithStaleEtagReturns412ProblemDetail() throws Exception {
        when(productService.update(eq("abc"), eq(2L), any(ProductRequestDTO.class)))
                .thenThrow(new ProductVersionConflictException("abc"));

        mvc.perform(put("/api/product/abc").with(admin()).header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isPreconditionFailed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Precondition Failed"))
                .andExpect(jsonPath("$.instance").value("/api/product/abc"));
    }

    @Test
    void deleteRequiresIfMatch() throws Exception {
        mvc.perform(delete("/api/product/abc").with(admin()))
                .andExpect(status().isPreconditionRequired());
        verifyNoInteractions(productService);
    }

    @Test
    void deleteWithStaleEtagReturns412() throws Exception {
        doThrow(new ProductVersionConflictException("abc")).when(productService).delete("abc", 2L);

        mvc.perform(delete("/api/product/abc").with(admin()).header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isPreconditionFailed());
        verify(productService).delete("abc", 2L);
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static ProductResponseDTO product(long version) {
        return new ProductResponseDTO("abc", "Teclado", "Teclado mecânico RGB", new BigDecimal("349.90"),
                "Periféricos", null, version);
    }
}
