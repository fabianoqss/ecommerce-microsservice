package com.example.ecommerce.product.infra.mongo;

import com.example.ecommerce.product.domain.entity.Product;
import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@Component
public class ProductVersionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductVersionBackfill.class);

    private final MongoTemplate mongoTemplate;

    public ProductVersionBackfill(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UpdateResult result = mongoTemplate.updateMulti(
                Query.query(where("version").exists(false)),
                new Update().set("version", 0L),
                Product.class);
        if (result.getModifiedCount() > 0) {
            log.info("Version backfill applied to {} products", result.getModifiedCount());
        }
    }
}
