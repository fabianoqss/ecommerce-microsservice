package com.example.ecommerce.product.service;


import com.example.ecommerce.product.domain.entity.Product;
import com.example.ecommerce.product.dto.request.ProductRequestDTO;
import com.example.ecommerce.product.dto.response.PageResponseDTO;
import com.example.ecommerce.product.dto.response.ProductResponseDTO;
import com.example.ecommerce.product.exception.ResourceNotFoundException;
import com.example.ecommerce.product.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;



@Service
public class ProductService {

    @Autowired
    private ProductRepository repository;


    public ProductResponseDTO findByID(String id){
        Product product = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado através do id: " + id));


        return ProductResponseDTO.from(product);
    }


    public PageResponseDTO<ProductResponseDTO> findAll(int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("id"));
        return PageResponseDTO.from(repository.findAll(pageable).map(ProductResponseDTO::from));
    }

    public ProductResponseDTO insert(ProductRequestDTO dto){
        Product product = new Product();
        copyDtoToEntity(dto, product);
        product = repository.save(product);
        return ProductResponseDTO.from(product);
    }

    public ProductResponseDTO update(String id, ProductRequestDTO dto){

        Product product = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        copyDtoToEntity(dto, product);

        product = repository.save(product);

        return ProductResponseDTO.from(product);
    }

    public void delete(String id){
        if(!repository.existsById(id)){
            throw new ResourceNotFoundException("Product not found");
        }
        repository.deleteById(id);
    }


    private void copyDtoToEntity(ProductRequestDTO dto, Product entity){
        entity.setName(dto.name());
        entity.setCategory(dto.category());
        entity.setDescription(dto.description());
        entity.setPrice(dto.price());
        entity.setImageUrl(dto.imageUrl());
    }
}
