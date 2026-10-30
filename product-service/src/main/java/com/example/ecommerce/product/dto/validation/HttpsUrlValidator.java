package com.example.ecommerce.product.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;

public class HttpsUrlValidator implements ConstraintValidator<HttpsUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            return false;
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getRawUserInfo() != null || uri.getUserInfo() != null) {
            return false;
        }
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.isBlank() || rawAuthority.contains("@")) {
            return false;
        }
        String host = uri.getHost();
        return host != null && !host.isBlank();
    }
}
