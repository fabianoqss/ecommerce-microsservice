package com.ecommerce.product_service.repositories;

import com.ecommerce.product_service.entities.Product;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProductRepository extends MongoRepository<Product, String> {
}
