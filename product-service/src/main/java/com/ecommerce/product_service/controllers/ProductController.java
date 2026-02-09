package com.ecommerce.product_service.controllers;


import com.ecommerce.product_service.dto.ProductDTO;
import com.ecommerce.product_service.services.ProductService;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    @GetMapping(value = "/{id}")
    public ResponseEntity<ProductDTO> findById(@PathVariable String id){
        ProductDTO dto = productService.findByID(id);

        return ResponseEntity.ok(dto);
    }

    @GetMapping
    public ResponseEntity<List<ProductDTO>> findAll(Pageable pageable){
        List<ProductDTO> dto = productService.findAll();

        return ResponseEntity.ok(dto);
    }


}
