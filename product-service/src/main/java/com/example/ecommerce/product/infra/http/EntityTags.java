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
                    "Provide the If-Match header with the ETag obtained from the product GET request");
        }
        Matcher matcher = STRONG_VERSION_TAG.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "If-Match must contain a single strong product ETag, for example \"3\"");
        }
        return Long.parseLong(matcher.group(1));
    }
}
