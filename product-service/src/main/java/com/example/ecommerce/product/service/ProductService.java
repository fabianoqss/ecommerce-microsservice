package com.example.ecommerce.product.service;


import com.example.ecommerce.product.domain.entity.Product;
import com.example.ecommerce.product.dto.request.ProductRequestDTO;
import com.example.ecommerce.product.dto.response.PageResponseDTO;
import com.example.ecommerce.product.dto.response.ProductResponseDTO;
import com.example.ecommerce.product.exception.ProductVersionConflictException;
import com.example.ecommerce.product.exception.ResourceNotFoundException;
import com.example.ecommerce.product.repository.ProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Objects;


@Service
public class ProductService {

    static final String CONFLICT_METRIC = "product.write.conflicts";

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository repository;
    private final MeterRegistry meterRegistry;

    public ProductService(ProductRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    public ProductResponseDTO findByID(String id){
        return ProductResponseDTO.from(findOrThrow(id));
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

    public ProductResponseDTO update(String id, long expectedVersion, ProductRequestDTO dto){
        Product product = findOrThrow(id);
        ensureVersionMatches(product, expectedVersion, "update");

        copyDtoToEntity(dto, product);
        try {
            return ProductResponseDTO.from(repository.save(product));
        } catch (OptimisticLockingFailureException e) {
            throw conflict(id, "update");
        }
    }

    public void delete(String id, long expectedVersion){
        Product product = findOrThrow(id);
        ensureVersionMatches(product, expectedVersion, "delete");

        try {
            repository.delete(product);
        } catch (OptimisticLockingFailureException e) {
            throw conflict(id, "delete");
        }
    }

    private void ensureVersionMatches(Product product, long expectedVersion, String operation) {
        if (!Objects.equals(product.getVersion(), expectedVersion)) {
            log.debug("Conflito de versão no {} do produto {}: esperada {}, atual {}",
                    operation, product.getId(), expectedVersion, product.getVersion());
            throw conflict(product.getId(), operation);
        }
    }

    private ProductVersionConflictException conflict(String id, String operation) {
        meterRegistry.counter(CONFLICT_METRIC, "operation", operation).increment();
        return new ProductVersionConflictException(id);
    }

    private Product findOrThrow(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado através do id: " + id));
    }

    private void copyDtoToEntity(ProductRequestDTO dto, Product entity){
        entity.setName(dto.name());
        entity.setCategory(dto.category());
        entity.setDescription(dto.description());
        entity.setPrice(dto.price());
        entity.setImageUrl(dto.imageUrl());
    }
}
