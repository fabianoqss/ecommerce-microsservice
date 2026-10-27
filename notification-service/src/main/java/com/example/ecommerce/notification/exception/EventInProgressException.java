package com.example.ecommerce.notification.exception;

import java.util.UUID;

public class EventInProgressException extends RuntimeException {

    public EventInProgressException(UUID eventId) {
        super("Evento em processamento; aguarde uma nova tentativa: eventId=" + eventId);
    }
}
