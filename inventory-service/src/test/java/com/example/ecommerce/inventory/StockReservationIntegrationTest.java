package com.example.ecommerce.inventory;

import com.example.ecommerce.inventory.domain.Inventory;
import com.example.ecommerce.inventory.domain.ReservationStatus;
import com.example.ecommerce.inventory.repository.InventoryRepository;
import com.example.ecommerce.inventory.repository.StockReservationItemRepository;
import com.example.ecommerce.inventory.repository.StockReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "DB_PASSWORD=unused"
})
@AutoConfigureMockMvc
@Testcontainers
class StockReservationIntegrationTest {

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

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        reservationItemRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        inventoryRepository.deleteAllInBatch();

        saveSku("SKU-A", 100);
        saveSku("SKU-B", 100);
    }

    @Test
    void repeatedReserveDoesNotDecrementStockTwiceAndNormalizesItems() throws Exception {
        UUID reservationId = UUID.randomUUID();
        double createdBefore = reservationMetric("created");
        double replayedBefore = reservationMetric("replayed");

        // Primeira chamada: itens fora de ordem e com SKU-B duplicado (2 + 1 = 3).
        mvc.perform(put("/api/inventory/reservations/" + reservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    {"skuCode": "SKU-B", "quantity": 2},
                                    {"skuCode": "SKU-A", "quantity": 5},
                                    {"skuCode": "SKU-B", "quantity": 1}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items[0].skuCode").value("SKU-A"))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.items[1].skuCode").value("SKU-B"))
                .andExpect(jsonPath("$.items[1].quantity").value(3));

        assertThat(quantityOf("SKU-A")).isEqualTo(95);
        assertThat(quantityOf("SKU-B")).isEqualTo(97);

        // Segunda chamada (replay): mesmos itens já normalizados => 200 OK sem nova baixa.
        mvc.perform(put("/api/inventory/reservations/" + reservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    {"skuCode": "SKU-A", "quantity": 5},
                                    {"skuCode": "SKU-B", "quantity": 3}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        assertThat(quantityOf("SKU-A")).isEqualTo(95);
        assertThat(quantityOf("SKU-B")).isEqualTo(97);
        assertThat(reservationMetric("created")).isEqualTo(createdBefore + 1.0);
        assertThat(reservationMetric("replayed")).isEqualTo(replayedBefore + 1.0);

        // Consulta GET confirma o estado persistido.
        mvc.perform(get("/api/inventory/reservations/" + reservationId).with(serviceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void reserveWithDivergentItemsReturns422AndPreservesStock() throws Exception {
        UUID reservationId = UUID.randomUUID();
        double conflictBefore = reservationMetric("conflict");

        mvc.perform(put("/api/inventory/reservations/" + reservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{"skuCode": "SKU-A", "quantity": 4}]}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/inventory/reservations/" + reservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{"skuCode": "SKU-A", "quantity": 6}]}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422));

        assertThat(quantityOf("SKU-A")).isEqualTo(96);
        assertThat(reservationMetric("conflict")).isEqualTo(conflictBefore + 1.0);
    }

    @Test
    void releaseTwiceIncrementsStockOnlyOnce() throws Exception {
        UUID reservationId = UUID.randomUUID();
        double releasedBefore = releaseMetric("released");
        double noopBefore = releaseMetric("noop");

        mvc.perform(put("/api/inventory/reservations/" + reservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{"skuCode": "SKU-A", "quantity": 10}, {"skuCode": "SKU-B", "quantity": 5}]}
                                """))
                .andExpect(status().isCreated());

        assertThat(quantityOf("SKU-A")).isEqualTo(90);
        assertThat(quantityOf("SKU-B")).isEqualTo(95);

        mvc.perform(delete("/api/inventory/reservations/" + reservationId).with(serviceToken()))
                .andExpect(status().isNoContent());

        assertThat(quantityOf("SKU-A")).isEqualTo(100);
        assertThat(quantityOf("SKU-B")).isEqualTo(100);

        mvc.perform(delete("/api/inventory/reservations/" + reservationId).with(serviceToken()))
                .andExpect(status().isNoContent());

        assertThat(quantityOf("SKU-A")).isEqualTo(100);
        assertThat(quantityOf("SKU-B")).isEqualTo(100);
        assertThat(releaseMetric("released")).isEqualTo(releasedBefore + 1.0);
        assertThat(releaseMetric("noop")).isEqualTo(noopBefore + 1.0);

        mvc.perform(get("/api/inventory/reservations/" + reservationId).with(serviceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void releaseBeforeReserveCreatesTombstoneAndSubsequentReserveReturns410Gone() throws Exception {
        UUID reservationId = UUID.randomUUID();
        double tombstoneBefore = releaseMetric("tombstone");
        double goneBefore = reservationMetric("gone");

        mvc.perform(delete("/api/inventory/reservations/" + reservationId).with(serviceToken()))
                .andExpect(status().isNoContent());

        assertThat(releaseMetric("tombstone")).isEqualTo(tombstoneBefore + 1.0);
        assertThat(quantityOf("SKU-A")).isEqualTo(100);

        mvc.perform(get("/api/inventory/reservations/" + reservationId).with(serviceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.items").isEmpty());

        mvc.perform(put("/api/inventory/reservations/" + reservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{"skuCode": "SKU-A", "quantity": 5}]}
                                """))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(410));

        assertThat(quantityOf("SKU-A")).isEqualTo(100);
        assertThat(reservationMetric("gone")).isEqualTo(goneBefore + 1.0);
    }

    @Test
    void insufficientStockOrMissingSkuInOneItemRollsBackAllItems() throws Exception {
        UUID reservationId = UUID.randomUUID();
        double insufficientBefore = reservationMetric("insufficient");

        // SKU-A tem estoque (100 >= 10), mas SKU-B pede 150 (> 100) => rollback total.
        mvc.perform(put("/api/inventory/reservations/" + reservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    {"skuCode": "SKU-A", "quantity": 10},
                                    {"skuCode": "SKU-B", "quantity": 150}
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));

        assertThat(quantityOf("SKU-A")).isEqualTo(100);
        assertThat(quantityOf("SKU-B")).isEqualTo(100);
        assertThat(reservationRepository.findById(reservationId)).isEmpty();
        assertThat(reservationMetric("insufficient")).isEqualTo(insufficientBefore + 1.0);

        // SKU inexistente também retorna 409 e faz rollback total.
        UUID missingSkuReservationId = UUID.randomUUID();
        mvc.perform(put("/api/inventory/reservations/" + missingSkuReservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    {"skuCode": "SKU-A", "quantity": 5},
                                    {"skuCode": "SKU-NAO-EXISTE", "quantity": 1}
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(quantityOf("SKU-A")).isEqualTo(100);
        assertThat(reservationRepository.findById(missingSkuReservationId)).isEmpty();
        assertThat(reservationMetric("insufficient")).isEqualTo(insufficientBefore + 2.0);
    }

    @Test
    void concurrentPutOfSameReservationDecrementsStockExactlyOnce() throws Exception {
        int threads = 8;
        UUID reservationId = UUID.randomUUID();
        String payload = """
                {
                  "items": [
                    {"skuCode": "SKU-A", "quantity": 7},
                    {"skuCode": "SKU-B", "quantity": 4}
                  ]
                }
                """;

        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    barrier.await();
                    return mvc.perform(put("/api/inventory/reservations/" + reservationId)
                                    .with(serviceToken())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });
            }
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }

            assertThat(statuses).containsOnly(201, 200);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(threads - 1);
        }

        assertThat(quantityOf("SKU-A")).isEqualTo(93);
        assertThat(quantityOf("SKU-B")).isEqualTo(96);
    }

    @Test
    void concurrentDeleteOfSameReservationIncrementsStockExactlyOnce() throws Exception {
        int threads = 8;
        UUID reservationId = UUID.randomUUID();
        double releasedBefore = releaseMetric("released");
        double noopBefore = releaseMetric("noop");

        mvc.perform(put("/api/inventory/reservations/" + reservationId)
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    {"skuCode": "SKU-A", "quantity": 12},
                                    {"skuCode": "SKU-B", "quantity": 8}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        assertThat(quantityOf("SKU-A")).isEqualTo(88);
        assertThat(quantityOf("SKU-B")).isEqualTo(92);

        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    barrier.await();
                    return mvc.perform(delete("/api/inventory/reservations/" + reservationId)
                                    .with(serviceToken()))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });
            }
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isEqualTo(204);
            }
        }

        assertThat(quantityOf("SKU-A")).isEqualTo(100);
        assertThat(quantityOf("SKU-B")).isEqualTo(100);
        assertThat(releaseMetric("released")).isEqualTo(releasedBefore + 1.0);
        assertThat(releaseMetric("noop")).isEqualTo(noopBefore + (threads - 1));
    }

    @Test
    void concurrentPutAndDeleteNeverLeaveStockInconsistent() throws Exception {
        int iterations = 25;
        int putThreads = 4;
        int deleteThreads = 4;
        int totalThreads = putThreads + deleteThreads;
        String payload = """
                {
                  "items": [
                    {"skuCode": "SKU-A", "quantity": 5},
                    {"skuCode": "SKU-B", "quantity": 3}
                  ]
                }
                """;

        try (ExecutorService pool = Executors.newFixedThreadPool(totalThreads)) {
            for (int round = 0; round < iterations; round++) {
                UUID reservationId = UUID.randomUUID();
                CyclicBarrier barrier = new CyclicBarrier(totalThreads);
                List<Callable<Integer>> tasks = new ArrayList<>();

                for (int i = 0; i < putThreads; i++) {
                    tasks.add(() -> {
                        barrier.await();
                        return mvc.perform(put("/api/inventory/reservations/" + reservationId)
                                        .with(serviceToken())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    });
                }
                for (int i = 0; i < deleteThreads; i++) {
                    tasks.add(() -> {
                        barrier.await();
                        return mvc.perform(delete("/api/inventory/reservations/" + reservationId)
                                        .with(serviceToken()))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    });
                }

                List<Future<Integer>> futures = pool.invokeAll(tasks);
                for (Future<Integer> f : futures) {
                    int status = f.get();
                    assertThat(status).isIn(200, 201, 204, 410);
                }

                // Ao término da corrida PUT vs DELETE, como ao menos um DELETE completou
                // (seja criando lápide antes do PUT ou liberando após o commit do PUT),
                // o estado final da reserva é sempre RELEASED e o saldo líquido de estoque é inalterado.
                var reservation = reservationRepository.findById(reservationId).orElseThrow();
                assertThat(reservation.getStatus())
                        .as("Rodada %d: reserva deve terminar RELEASED", round)
                        .isEqualTo(ReservationStatus.RELEASED);
                assertThat(quantityOf("SKU-A"))
                        .as("Rodada %d: saldo de SKU-A inconsistente", round)
                        .isEqualTo(100);
                assertThat(quantityOf("SKU-B"))
                        .as("Rodada %d: saldo de SKU-B inconsistente", round)
                        .isEqualTo(100);
            }
        }
    }

    @Test
    void deprecatedLegacyReserveAndReleaseStillWork() throws Exception {
        String payload = """
                {"items": [{"skuCode": "SKU-A", "quantity": 6}]}
                """;

        mvc.perform(post("/api/inventory/reserve")
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNoContent());

        assertThat(quantityOf("SKU-A")).isEqualTo(94);

        mvc.perform(post("/api/inventory/release")
                        .with(serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNoContent());

        assertThat(quantityOf("SKU-A")).isEqualTo(100);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceToken() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));
    }

    private void saveSku(String sku, int quantity) {
        Inventory inv = new Inventory();
        inv.setSkuCode(sku);
        inv.setQuantity(quantity);
        inventoryRepository.save(inv);
    }

    private int quantityOf(String sku) {
        return inventoryRepository.findBySkuCodeIn(List.of(sku)).getFirst().getQuantity();
    }

    private double reservationMetric(String result) {
        return meterRegistry.counter("inventory.reservations", "result", result).count();
    }

    private double releaseMetric(String result) {
        return meterRegistry.counter("inventory.releases", "result", result).count();
    }
}
