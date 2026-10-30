package com.example.ecommerce.inventory;

import com.example.ecommerce.inventory.repository.InventoryRepository;
import com.example.ecommerce.inventory.repository.StockReservationItemRepository;
import com.example.ecommerce.inventory.repository.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "DB_PASSWORD=unused"
})
@AutoConfigureMockMvc
@Testcontainers
class InventoryInsertIntegrationTest {

    private static final String SKU = "670000000000000000000001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.12");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private StockReservationItemRepository reservationItemRepository;

    @BeforeEach
    void setUp() {
        reservationItemRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        inventoryRepository.deleteAllInBatch();
    }

    @Test
    void duplicatedSkuCodeReturnsConflictAndKeepsOriginalQuantity() throws Exception {
        mvc.perform(post("/api/inventory").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKU, 100)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/inventory").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SKU, 5)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(containsString(SKU)));

        assertThat(inventoryRepository.findBySkuCodeIn(List.of(SKU)))
                .singleElement()
                .satisfies(inventory -> assertThat(inventory.getQuantity()).isEqualTo(100));
    }

    private static String body(String skuCode, int quantity) {
        return "{\"skuCode\":\"" + skuCode + "\",\"quantity\":" + quantity + "}";
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
