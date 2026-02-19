package com.example.ecommerce.order_service.client;


import com.example.ecommerce.order_service.dtos.ProductDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "product-service")
public interface ProductClient {

    @GetMapping("/api/products/{id}")
    ProductDTO getProductById(@PathVariable("id") String id);
}
