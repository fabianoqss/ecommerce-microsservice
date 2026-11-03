package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.domain.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ProductRepositoryCustom {

    Page<Product> search(String name, String category, Pageable pageable);

    List<String> findCategories();
}
