package com.ecommerce.product_service.entities;


import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;

@Document(value = "products")
public class Product {

    @Id
    private String id;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;

    public Product() {
    }

    public Product(String id, String name, String description, BigDecimal price, String category) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
    }



    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        category = category;
    }
}
