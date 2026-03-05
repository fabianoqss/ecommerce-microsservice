package com.example.ecommerce.inventory_service.controllers;


import com.example.ecommerce.inventory_service.dto.InventoryDTO;
import com.example.ecommerce.inventory_service.dto.InventoryRequestDTO;
import com.example.ecommerce.inventory_service.dto.InventoryUpdateDTO;
import com.example.ecommerce.inventory_service.services.InventoryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/api/inventory")
@RestController
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<InventoryDTO> isInStock(@RequestParam("skuCode") List<String> skuCode){
        return inventoryService.isInStock(skuCode);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryDTO insert(@Valid @RequestBody InventoryRequestDTO dto){
        return inventoryService.insert(dto);
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public InventoryDTO update(@PathVariable Long id, @Valid @RequestBody InventoryUpdateDTO dto){
        return inventoryService.update(id, dto);
    }

}
