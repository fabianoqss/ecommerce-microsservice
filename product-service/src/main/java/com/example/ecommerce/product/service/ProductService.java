package com.example.ecommerce.product.service;


import com.example.ecommerce.product.dto.ProductDTO;
import com.example.ecommerce.product.dto.PageResponseDTO;
import com.example.ecommerce.product.domain.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;



@Service
public class ProductService {

    @Autowired
    private ProductRepository repository;


    public ProductDTO findByID(String id){
        Product product = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado através do id: " + id));


        return new ProductDTO(product);
    }


    public PageResponseDTO<ProductDTO> findAll(int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("id"));
        return PageResponseDTO.from(repository.findAll(pageable).map(ProductDTO::new));
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
        entity.setImageUrl(dto.getImageUrl());
    }
}
