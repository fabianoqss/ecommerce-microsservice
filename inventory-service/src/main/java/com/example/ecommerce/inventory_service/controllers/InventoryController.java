package com.example.ecommerce.inventory_service.controllers;


import com.example.ecommerce.inventory_service.dto.InventoryDTO;
import com.example.ecommerce.inventory_service.dto.InventoryRequestDTO;
import com.example.ecommerce.inventory_service.dto.InventoryUpdateDTO;
import com.example.ecommerce.inventory_service.services.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/api/inventory")
@RestController
@Tag(name = "Inventory", description = "Controller for Inventory")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    @Operation(
            description = "Check stock for one or more SKU codes",
            summary = "Check if items are in stock",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200")
            }
    )
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<InventoryDTO> isInStock(@RequestParam("skuCode") List<String> skuCode){
        return inventoryService.isInStock(skuCode);
    }

    @Operation(
            description = "Insert a new inventory item",
            summary = "Insert inventory item",
            responses = {
                    @ApiResponse(description = "Created", responseCode = "201"),
                    @ApiResponse(description = "Bad Request", responseCode = "400")
            }
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryDTO insert(@Valid @RequestBody InventoryRequestDTO dto){
        return inventoryService.insert(dto);
    }

    @Operation(
            description = "Update quantity of an inventory item",
            summary = "Update inventory item",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Not Found", responseCode = "404"),
                    @ApiResponse(description = "Bad Request", responseCode = "400")
            }
    )
    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public InventoryDTO update(@PathVariable Long id, @Valid @RequestBody InventoryUpdateDTO dto){
        return inventoryService.update(id, dto);
    }

}
