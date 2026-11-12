package com.example.ecommerce.notification.service.port;

import java.util.Objects;
import java.util.UUID;

public record AcquireResult(AcquireStatus status, UUID ownerToken) {

    public AcquireResult {
        Objects.requireNonNull(status, "status is required");
        if ((status == AcquireStatus.ACQUIRED) != (ownerToken != null)) {
            throw new IllegalArgumentException("ownerToken must be present only for a successful acquisition");
        }
    }
}
