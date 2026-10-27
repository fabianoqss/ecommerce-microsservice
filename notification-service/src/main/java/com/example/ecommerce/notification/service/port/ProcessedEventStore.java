package com.example.ecommerce.notification.service.port;

import java.util.UUID;

public interface ProcessedEventStore {

    AcquireResult tryAcquire(UUID eventId);

    boolean markProcessed(UUID eventId, UUID ownerToken);

    void release(UUID eventId, UUID ownerToken);
}
