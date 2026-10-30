package com.example.ecommerce.product.controller;

import com.example.ecommerce.product.dto.request.ProductRequestDTO;
import com.example.ecommerce.product.dto.response.ProductResponseDTO;
import com.example.ecommerce.product.exception.ResourceNotFoundException;
import com.example.ecommerce.product.infra.security.SecurityConfig;
import com.example.ecommerce.product.service.ProductService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductErrorResponseTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void missingProductReturnsProblemDetail() throws Exception {
        when(productService.findByID("missing")).thenThrow(new ResourceNotFoundException("Produto não encontrado"));

        mvc.perform(get("/api/product/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Produto não encontrado"))
                .andExpect(jsonPath("$.instance").value("/api/product/missing"));
    }

    @Test
    void invalidProductReturnsProblemDetailWithFieldErrors() throws Exception {
        mvc.perform(post("/api/product")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/product"))
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.price").exists());
    }

    @Test
    void invalidPaginationReturnsProblemDetail() throws Exception {
        mvc.perform(get("/api/product").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/product").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
            "http://example.com/a.png",
            "/img/a.png",
            "https://user:pass@example.com/a.png"
    })
    void invalidImageUrlReturnsBadRequestProblemDetail(String invalidImageUrl) throws Exception {
        String payload = """
                {"name":"Teclado","description":"Teclado mecânico RGB","price":349.90,"category":"Periféricos","imageUrl":"%s"}
                """.formatted(invalidImageUrl);

        mvc.perform(post("/api/product")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fields.imageUrl").exists());
        verifyNoInteractions(productService);
    }

    @Test
    void imageUrlExceedingMaxLengthReturnsBadRequestProblemDetail() throws Exception {
        String longUrl = "https://example.com/" + "a".repeat(2030);
        String payload = """
                {"name":"Teclado","description":"Teclado mecânico RGB","price":349.90,"category":"Periféricos","imageUrl":"%s"}
                """.formatted(longUrl);

        mvc.perform(post("/api/product")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fields.imageUrl").exists());
        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankCategoryReturnsBadRequestProblemDetail(String blankCategory) throws Exception {
        String payload = """
                {"name":"Teclado","description":"Teclado mecânico RGB","price":349.90,"category":"%s"}
                """.formatted(blankCategory);

        mvc.perform(post("/api/product")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fields.category").exists());
        verifyNoInteractions(productService);
    }

    @Test
    void categoryExceedingMaxLengthReturnsBadRequestProblemDetail() throws Exception {
        String payload = """
                {"name":"Teclado","description":"Teclado mecânico RGB","price":349.90,"category":"%s"}
                """.formatted("c".repeat(101));

        mvc.perform(post("/api/product")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fields.category").exists());
        verifyNoInteractions(productService);
    }

    @Test
    void descriptionExceedingMaxLengthReturnsBadRequestProblemDetail() throws Exception {
        String payload = """
                {"name":"Teclado","description":"%s","price":349.90,"category":"Periféricos"}
                """.formatted("d".repeat(2001));

        mvc.perform(post("/api/product")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fields.description").exists());
        verifyNoInteractions(productService);
    }

    @Test
    void validProductWithNullImageUrlPassesValidation() throws Exception {
        when(productService.insert(any())).thenAnswer(invocation -> {
            ProductRequestDTO req = invocation.getArgument(0);
            return new ProductResponseDTO("abc", req.name(), req.description(), req.price(), req.category(), req.imageUrl());
        });
        String payload = """
                {"name":"Teclado","description":"Teclado mecânico RGB","price":349.90,"category":"Periféricos","imageUrl":null}
                """;

        mvc.perform(post("/api/product")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());
    }
}
