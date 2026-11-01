package com.example.ecommerce.product;

import com.example.ecommerce.product.domain.entity.Product;
import com.example.ecommerce.product.dto.request.ProductRequestDTO;
import com.example.ecommerce.product.dto.response.ProductResponseDTO;
import com.example.ecommerce.product.exception.ProductVersionConflictException;
import com.example.ecommerce.product.infra.mongo.ProductVersionBackfill;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.service.ProductService;
import com.example.ecommerce.product.support.MongoContainerTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductOptimisticLockingIntegrationTest extends MongoContainerTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ProductVersionBackfill backfill;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void insertStartsAtVersionZeroAndEachUpdateIncrementsIt() {
        ProductResponseDTO created = productService.insert(request("Teclado"));
        assertThat(created.version()).isZero();

        ProductResponseDTO updated = productService.update(created.id(), 0L, request("Teclado TKL"));

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.name()).isEqualTo("Teclado TKL");
    }

    @Test
    void updateWithStaleVersionIsRejectedAndKeepsTheWinningWrite() {
        ProductResponseDTO created = productService.insert(request("Teclado"));
        productService.update(created.id(), 0L, request("Edição do admin A"));
        double before = conflicts("update");

        assertThatThrownBy(() -> productService.update(created.id(), 0L, request("Edição do admin B")))
                .isInstanceOf(ProductVersionConflictException.class);

        Product stored = repository.findById(created.id()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Edição do admin A");
        assertThat(stored.getVersion()).isEqualTo(1L);
        assertThat(conflicts("update")).isEqualTo(before + 1);
    }

    @Test
    void deleteWithStaleVersionIsRejectedAndKeepsTheDocument() {
        ProductResponseDTO created = productService.insert(request("Teclado"));
        productService.update(created.id(), 0L, request("Teclado TKL"));
        double before = conflicts("delete");

        assertThatThrownBy(() -> productService.delete(created.id(), 0L))
                .isInstanceOf(ProductVersionConflictException.class);

        assertThat(repository.existsById(created.id())).isTrue();
        assertThat(conflicts("delete")).isEqualTo(before + 1);

        productService.delete(created.id(), 1L);
        assertThat(repository.existsById(created.id())).isFalse();
    }

    @Test
    void databaseRejectsSaveOfAnEntityLoadedBeforeAConcurrentWrite() {
        ProductResponseDTO created = productService.insert(request("Teclado"));
        Product readByA = repository.findById(created.id()).orElseThrow();
        Product readByB = repository.findById(created.id()).orElseThrow();
        Product readByC = repository.findById(created.id()).orElseThrow();

        readByA.setName("A");
        repository.save(readByA);
        readByB.setName("B");

        assertThatThrownBy(() -> repository.save(readByB)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> repository.delete(readByC)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(readByB.getVersion()).isEqualTo(1L);
        assertThat(repository.findById(created.id()).orElseThrow().getName()).isEqualTo("A");
    }

    @Test
    void backfillMakesLegacyDocumentsWithoutVersionUpdatable() {
        String legacyId = new ObjectId().toHexString();
        mongoTemplate.getCollection("products").insertOne(new Document("_id", new ObjectId(legacyId))
                .append("name", "Legado")
                .append("description", "Documento criado antes do versionamento")
                .append("price", new Decimal128(new BigDecimal("10.00")))
                .append("category", "Livros"));

        backfill.run(null);

        assertThat(productService.findByID(legacyId).version()).isZero();
        ProductResponseDTO updated = productService.update(legacyId, 0L, request("Legado atualizado"));
        assertThat(updated.version()).isEqualTo(1L);
        assertThat(repository.count()).isEqualTo(1);
    }

    private double conflicts(String operation) {
        Counter counter = meterRegistry.find("product.write.conflicts").tag("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }

    private static ProductRequestDTO request(String name) {
        return new ProductRequestDTO(name, "Descrição do produto", new BigDecimal("349.90"), "Periféricos", null);
    }
}
