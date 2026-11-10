package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.domain.entity.Product;
import org.bson.Document;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductRepositoryCustomImplTest {

    @ParameterizedTest
    @MethodSource("literalNames")
    void nameSearchUsesPortableLiteralRegexIgnoringCase(String name, String expectedRegex) {
        var mongoTemplate = mock(MongoTemplate.class);
        var repository = new ProductRepositoryCustomImpl(mongoTemplate);

        repository.search(name, null, PageRequest.of(0, 8));

        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(Product.class));
        var nameFilter = query.getValue().getQueryObject()
                .getList("$and", Document.class).getFirst().get("name");

        assertThat(nameFilter).isInstanceOf(Document.class);
        var nameCriteria = (Document) nameFilter;
        assertThat(nameCriteria.getString("$regex"))
                .isEqualTo(expectedRegex)
                .doesNotContain("\\Q", "\\E");
        assertThat(nameCriteria.getString("$options")).isEqualTo("i");
    }

    private static Stream<Arguments> literalNames() {
        return Stream.of(
                Arguments.of("Teclado", "Teclado"),
                Arguments.of("Teclado (TKL)", "Teclado \\(TKL\\)"),
                Arguments.of(".*", "\\.\\*"),
                Arguments.of("(a+)+$", "\\(a\\+\\)\\+\\$"),
                Arguments.of("a\\b", "a\\\\b"),
                Arguments.of("\\.^$|?*+()[]{}-/#", "\\\\\\.\\^\\$\\|\\?\\*\\+\\(\\)\\[\\]\\{\\}\\-\\/\\#"),
                Arguments.of("  Áudio Mecânico  ", "Áudio Mecânico"),
                Arguments.of("a\tb\nc", "a\tb\nc"));
    }
}
