package com.example.ecommerce.order_service.controllers;

import com.example.ecommerce.order_service.client.ProductClient;
import com.example.ecommerce.order_service.dtos.ProductDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    @Autowired
    private ProductClient productClient;

    @GetMapping("/test-product/{id}")
    public ResponseEntity<?> testCommunication(@PathVariable String id){
        ProductDTO productDTO = productClient.getProductById(id);

        return ResponseEntity.ok("Comunicação OK! Produto recuperado: " + productDTO.name());
    }
}
