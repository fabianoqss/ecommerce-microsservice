package com.example.ecommerce.product.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mongodb.MongoDBContainer;

public abstract class MongoContainerTest {

    @ServiceConnection
    protected static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0.45");

    static {
        MONGO.start();
    }
}
