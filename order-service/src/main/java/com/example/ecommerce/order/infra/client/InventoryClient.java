package com.example.ecommerce.order.infra.client;

import com.example.ecommerce.order.dto.client.inventory.InventoryReservationResponseDTO;
import com.example.ecommerce.order.dto.client.inventory.InventoryReserveRequestDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

@FeignClient(name = "inventory-service", url = "${clients.inventory.url}", configuration = InventoryClientConfig.class)
public interface InventoryClient {

    @PutMapping("/api/inventory/reservations/{orderId}")
    void reserve(@PathVariable("orderId") UUID orderId, @RequestBody InventoryReserveRequestDTO request);

    @DeleteMapping("/api/inventory/reservations/{orderId}")
    void releaseReservation(@PathVariable("orderId") UUID orderId);

    @GetMapping("/api/inventory/reservations/{orderId}")
    InventoryReservationResponseDTO getReservation(@PathVariable("orderId") UUID orderId);
}
