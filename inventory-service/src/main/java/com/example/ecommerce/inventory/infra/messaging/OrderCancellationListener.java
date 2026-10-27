package com.example.ecommerce.inventory.infra.messaging;

import com.example.ecommerce.inventory.domain.ReservationMode;
import com.example.ecommerce.inventory.service.InventoryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCancellationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationListener.class);
    private static final String METRIC_CANCELLATIONS = "inventory.cancellations";
    private static final int MIN_SUPPORTED_VERSION = 3;

    private final InventoryService inventoryService;
    private final MeterRegistry meterRegistry;

    public OrderCancellationListener(InventoryService inventoryService, MeterRegistry meterRegistry) {
        this.inventoryService = inventoryService;
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_CANCELLED_QUEUE)
    public void handleOrderCancelled(OrderCancelledEventDTO event) {
        if (event == null || event.version() == null || event.version() < MIN_SUPPORTED_VERSION) {
            recordMetric("ignored", "ignored");
            log.info("Evento ORDER_CANCELLED ignorado por versão anterior à v3: eventId={}, orderId={}, version={}",
                    event != null ? event.eventId() : null,
                    event != null ? event.orderId() : null,
                    event != null ? event.version() : null);
            return;
        }

        if (event.orderId() == null || event.reservationMode() == null) {
            throw new IllegalArgumentException(
                    "Evento ORDER_CANCELLED v3 inválido (orderId ou reservationMode ausente): eventId=" + event.eventId());
        }

        if (event.reservationMode() == ReservationMode.LEDGER) {
            String result = inventoryService.release(event.orderId());
            recordMetric("ledger", result);
            return;
        }

        if (event.items() == null || event.items().isEmpty()) {
            throw new IllegalArgumentException(
                    "Evento ORDER_CANCELLED v3 LEGACY sem itens: orderId=" + event.orderId());
        }

        String result = inventoryService.releaseLegacy(event.orderId(), event.items());
        recordMetric("legacy", result);
    }

    private void recordMetric(String mode, String result) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_CANCELLATIONS, "mode", mode, "result", result).increment();
        }
    }
}
