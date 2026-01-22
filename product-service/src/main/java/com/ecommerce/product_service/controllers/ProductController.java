package com.ecommerce.product_service.controllers;


import com.ecommerce.product_service.dto.ProductDTO;
import com.ecommerce.product_service.services.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/products")
public class ProductController {

    @Autowired
    private ProductService productService;


    public ResponseEntity<ProductDTO> findById(){


        return null;
    }


}
