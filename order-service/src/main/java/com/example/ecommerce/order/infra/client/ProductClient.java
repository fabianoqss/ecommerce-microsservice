package com.example.ecommerce.order.infra.client;


import com.example.ecommerce.order.dto.response.ProductDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "product-service", url = "${clients.product.url}")
public interface ProductClient {

    @GetMapping("/api/product/{id}")
    ProductDTO getProductById(@PathVariable("id") String id);
}
