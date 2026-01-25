package com.ecommerce.product_service.services;


import com.ecommerce.product_service.dto.ProductDTO;
import com.ecommerce.product_service.entities.Product;
import com.ecommerce.product_service.repositories.ProductRepository;
import com.ecommerce.product_service.services.exceptions.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class ProductService {

    @Autowired
    private ProductRepository repository;




    public ProductDTO findByID(String id){
        Product product = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado através do id: " + id));


        return new ProductDTO(product.getName(), product.getDescription(), product.getPrice(), product.getCategory());
    }


    public List<ProductDTO> findAll(Pageable pageable) {
        return null;

    }
}
