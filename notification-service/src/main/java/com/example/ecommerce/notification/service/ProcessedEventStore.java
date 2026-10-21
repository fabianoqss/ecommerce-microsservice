package com.example.ecommerce.notification.service;

import java.util.UUID;

public interface ProcessedEventStore {

    boolean tryAcquire(UUID eventId);

    void markProcessed(UUID eventId);

    void release(UUID eventId);
}
