package com.example.ecommerce.inventory_service.controllers;


import com.example.ecommerce.inventory_service.dto.InventoryDTO;
import com.example.ecommerce.inventory_service.services.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping
@RestController("/api/inventory")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<InventoryDTO> isInStock(@RequestParam("skuCode")List<String> skuCode){

        return inventoryService.isInStock(skuCode);
    }

}
