package com.example.ecommerce.inventory_service.controllers;


import com.example.ecommerce.inventory_service.dto.InventoryDTO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping
@RestController("/api/inventory")
public class InventoryController {


    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<InventoryDTO> isInStock(@RequestParam("skuCode")List<String> skuCode){



        return null;
    }

}
