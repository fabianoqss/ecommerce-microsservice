package com.example.ecommerce.notification.dto;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventDTOCompatibilityTest {

    private final JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();

    @Test
    void readsV1EventWithNumericOrderId() {
        OrderEventDTO event = read("""
                {"eventId":"5f0c6a3e-1b2d-4c5e-8f9a-0b1c2d3e4f50","version":1,"orderId":42,
                 "userId":"user-sub","status":"CONFIRMED","totalValue":10.50,"orderTime":"2026-10-20T10:00:00"}
                """);

        assertThat(event.version()).isEqualTo(1);
        assertThat(event.orderId()).isEqualTo("42");
    }

    @Test
    void readsV2EventWithUuidOrderId() {
        OrderEventDTO event = read("""
                {"eventId":"5f0c6a3e-1b2d-4c5e-8f9a-0b1c2d3e4f50","version":2,"orderId":"019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b",
                 "userId":"user-sub","status":"CONFIRMED","totalValue":10.50,"orderTime":"2026-10-20T10:00:00"}
                """);

        assertThat(event.version()).isEqualTo(2);
        assertThat(event.orderId()).isEqualTo("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");
    }

    @Test
    void readsV3CancelledEventIgnoringAdditiveInventoryFields() {
        OrderEventDTO event = read("""
                {"eventId":"5f0c6a3e-1b2d-4c5e-8f9a-0b1c2d3e4f50","version":3,"orderId":"019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b",
                 "userId":"user-sub","status":"CANCELLED","totalValue":20.00,"orderTime":"2026-10-20T10:00:00",
                 "reservationMode":"LEDGER","items":[{"skuCode":"p1","quantity":2}]}
                """);

        assertThat(event.version()).isEqualTo(3);
        assertThat(event.orderId()).isEqualTo("019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b");
        assertThat(event.status()).isEqualTo("CANCELLED");
    }

    private OrderEventDTO read(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setInferredArgumentType(OrderEventDTO.class);
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), properties);
        return (OrderEventDTO) converter.fromMessage(message);
    }
}
