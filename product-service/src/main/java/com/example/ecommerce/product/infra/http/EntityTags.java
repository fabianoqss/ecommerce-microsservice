package com.example.ecommerce.product.infra.http;

import com.example.ecommerce.product.exception.PreconditionRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EntityTags {

    private static final Pattern STRONG_VERSION_TAG = Pattern.compile("^\"(\\d{1,18})\"$");

    private EntityTags() {
    }

    public static String fromVersion(long version) {
        return "\"" + version + "\"";
    }

    public static long requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            throw new PreconditionRequiredException(
                    "Informe o cabeçalho If-Match com o ETag obtido no GET do produto");
        }
        Matcher matcher = STRONG_VERSION_TAG.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "If-Match deve conter um único ETag forte do produto, por exemplo \"3\"");
        }
        return Long.parseLong(matcher.group(1));
    }
}
