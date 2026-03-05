package com.example.ecommerce.order_service.client;

import com.example.ecommerce.order_service.dtos.InventoryResponseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "inventory-service")
public interface InventoryClient {

    @GetMapping("/api/inventory")
    List<InventoryResponseDTO> isInStock(@RequestParam("skuCode") List<String> skuCode);
}
