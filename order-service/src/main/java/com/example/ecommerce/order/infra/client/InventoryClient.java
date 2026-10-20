package com.example.ecommerce.order.infra.client;

import com.example.ecommerce.order.dto.request.InventoryReserveRequestDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service", configuration = InventoryClientConfig.class)
public interface InventoryClient {

    @PostMapping("/api/inventory/reserve")
    void reserve(@RequestBody InventoryReserveRequestDTO request);

    @PostMapping("/api/inventory/release")
    void release(@RequestBody InventoryReserveRequestDTO request);
}
