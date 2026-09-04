package com.example.ecommerce.notification_service.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IdempotencyGuard {

    private final Set<UUID> processedEvents = ConcurrentHashMap.newKeySet();

    public boolean tryAcquire(UUID eventId) {
        return processedEvents.add(eventId);
    }

    public void release(UUID eventId) {
        processedEvents.remove(eventId);
    }
}
