package com.example.ecommerce.inventory;

import com.example.ecommerce.inventory.domain.entity.Inventory;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "DB_PASSWORD=unused"
})
@AutoConfigureMockMvc
@Testcontainers
class InventoryUpdateIntegrationTest {

    private static final String SKU = "670000000000000000000001";
    private static final String OTHER_SKU = "670000000000000000000002";

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
        inventoryRepository.saveAll(List.of(inventory(SKU, 10), inventory(OTHER_SKU, 20)));
    }

    @Test
    void updatesOnlyTheItemIdentifiedBySkuCode() throws Exception {
        mvc.perform(put("/api/inventory/" + SKU).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":75}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuCode").value(SKU))
                .andExpect(jsonPath("$.quantity").value(75))
                .andExpect(jsonPath("$.inStock").value(true));

        assertThat(inventoryRepository.findBySkuCode(SKU)).get()
                .satisfies(inventory -> assertThat(inventory.getQuantity()).isEqualTo(75));
        assertThat(inventoryRepository.findBySkuCode(OTHER_SKU)).get()
                .satisfies(inventory -> assertThat(inventory.getQuantity()).isEqualTo(20));
    }

    @Test
    void unknownSkuCodeReturnsNotFoundProblemDetail() throws Exception {
        String unknownSku = "670000000000000000000099";

        mvc.perform(put("/api/inventory/" + unknownSku).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Inventory item not found with skuCode: " + unknownSku));
    }

    private static Inventory inventory(String skuCode, int quantity) {
        Inventory inventory = new Inventory();
        inventory.setSkuCode(skuCode);
        inventory.setQuantity(quantity);
        return inventory;
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
