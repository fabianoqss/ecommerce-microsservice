package com.ecommerce.product_service.services;


import com.ecommerce.product_service.dto.ProductDTO;
import com.ecommerce.product_service.entities.Product;
import com.ecommerce.product_service.repositories.ProductRepository;
import com.ecommerce.product_service.services.exceptions.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class ProductService {

    @Autowired
    private ProductRepository repository;


    public ProductDTO findByID(String id){
        Product product = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado através do id: " + id));


        return new ProductDTO(product);
    }


    public List<ProductDTO> findAll() {
        return repository.findAll().stream()
                .map(x -> new ProductDTO(x))
                .toList();

    }

    public ProductDTO insert(ProductDTO dto){
        Product product = new Product();
        copyDtoToEntity(dto, product);
        product = repository.save(product);
        return new ProductDTO(product);
    }

    public ProductDTO update(String id, ProductDTO dto){

        Product product = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        copyDtoToEntity(dto, product);

        product = repository.save(product);

        return new ProductDTO(product);
    }

    public void delete(String id){
        if(!repository.existsById(id)){
            throw new ResourceNotFoundException("Product not found");
        }
        repository.deleteById(id);
    }


    private void copyDtoToEntity(ProductDTO dto, Product entity){
        entity.setName(dto.getName());
        entity.setCategory(dto.getCategory());
        entity.setDescription(dto.getDescription());
        entity.setPrice(dto.getPrice());

    }
}
