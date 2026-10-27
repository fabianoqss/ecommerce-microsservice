package com.example.ecommerce.notification.service.port;

import java.util.Objects;
import java.util.UUID;

public record AcquireResult(AcquireStatus status, UUID ownerToken) {

    public AcquireResult {
        Objects.requireNonNull(status, "status é obrigatório");
        if ((status == AcquireStatus.ACQUIRED) != (ownerToken != null)) {
            throw new IllegalArgumentException("ownerToken deve existir somente para uma aquisição bem-sucedida");
        }
    }
}
