package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.domain.entity.Product;
import com.example.ecommerce.product.support.MongoContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductSearchIntegrationTest extends MongoContainerTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 8, Sort.by("id"));

    @Autowired
    private ProductRepository repository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAll(List.of(
                product("p1", "Teclado Mecânico TKL", "Periféricos"),
                product("p2", "Mouse Gamer Sem Fio", "Periféricos"),
                product("p3", "Livro: Código Limpo", "Livros"),
                product("p4", "Livro: Arquitetura Limpa", "Livros"),
                product("p5", "Fone Bluetooth In-Ear", "Áudio")));
    }

    @Test
    void withoutFiltersReturnsEveryProductSortedById() {
        var page = repository.search(null, " ", FIRST_PAGE);

        assertThat(page.getContent()).extracting(Product::getId).containsExactly("p1", "p2", "p3", "p4", "p5");
        assertThat(page.getTotalElements()).isEqualTo(5);
    }

    @Test
    void nameMatchesAnyPartIgnoringCase() {
        var page = repository.search("TECL", null, FIRST_PAGE);

        assertThat(page.getContent()).extracting(Product::getId).containsExactly("p1");
    }

    @Test
    void categoryMatchesExactly() {
        var page = repository.search(null, "Livros", FIRST_PAGE);

        assertThat(page.getContent()).extracting(Product::getId).containsExactly("p3", "p4");
        assertThat(repository.search(null, "Livro", FIRST_PAGE).getContent()).isEmpty();
    }

    @Test
    void combinesNameAndCategory() {
        var page = repository.search("limpa", "Livros", FIRST_PAGE);

        assertThat(page.getContent()).extracting(Product::getId).containsExactly("p4");
    }

    @Test
    void treatsRegexCharactersAsLiteralText() {
        assertThat(repository.search(".*", null, FIRST_PAGE).getContent()).isEmpty();
        assertThat(repository.search("(a+)+$", null, FIRST_PAGE).getContent()).isEmpty();
        assertThat(repository.search("Livro:", null, FIRST_PAGE).getContent()).hasSize(2);
    }

    @Test
    void totalCountIgnoresPagination() {
        var page = repository.search(null, null, PageRequest.of(1, 2, Sort.by("id")));

        assertThat(page.getContent()).extracting(Product::getId).containsExactly("p3", "p4");
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    void categoriesAreDistinctAndSortedForPortuguese() {
        assertThat(repository.findCategories()).containsExactly("Áudio", "Livros", "Periféricos");
    }

    private static Product product(String id, String name, String category) {
        var product = new Product();
        product.setId(id);
        product.setName(name);
        product.setCategory(category);
        product.setPrice(new BigDecimal("10.00"));
        return product;
    }
}
