package com.example.ecommerce.product.dto.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HttpsUrlValidatorTest {

    private final HttpsUrlValidator validator = new HttpsUrlValidator();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "https://picsum.photos/seed/x/600/600",
            "HTTPS://cdn.example.com/a.png"
    })
    void acceptsNullAndValidHttpsUrls(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
            "http://example.com/a.png",
            "ftp://example.com/a.png",
            "/img/a.png",
            "#fragment",
            "https://",
            "https://user:pass@example.com/a.png",
            "https://@example.com/a.png",
            "https://exa mple.com/a.png",
            "https://example.com/a\n.png",
            "  ",
            ""
    })
    void rejectsInvalidUrls(String value) {
        assertThat(validator.isValid(value, null)).isFalse();
    }
}
