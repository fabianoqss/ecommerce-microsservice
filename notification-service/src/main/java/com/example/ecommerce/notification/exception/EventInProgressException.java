package com.example.ecommerce.notification.exception;

import java.util.UUID;

public class EventInProgressException extends RuntimeException {

    public EventInProgressException(UUID eventId) {
        super("Event is being processed; wait for another attempt: eventId=" + eventId);
    }
}
