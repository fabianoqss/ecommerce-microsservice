package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.domain.entity.Product;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class ProductRepositoryCustomImpl implements ProductRepositoryCustom {

    private static final Locale PT_BR = Locale.of("pt", "BR");

    private final MongoTemplate mongoTemplate;

    ProductRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Page<Product> search(String name, String category, Pageable pageable) {
        var filter = filterBy(name, category);

        var content = mongoTemplate.find(Query.of(filter).with(pageable), Product.class);
        return PageableExecutionUtils.getPage(content, pageable, () -> mongoTemplate.count(filter, Product.class));
    }

    @Override
    public List<String> findCategories() {
        var collator = Collator.getInstance(PT_BR);
        return mongoTemplate.findDistinct(new Query(), "category", Product.class, String.class).stream()
                .filter(category -> category != null && !category.isBlank())
                .sorted(collator)
                .toList();
    }

    private static Query filterBy(String name, String category) {
        var criteria = new ArrayList<Criteria>();

        if (hasText(name)) {
            var containsIgnoringCase = new Document("$regex", escapeRegexLiteral(name.trim()))
                    .append("$options", "i");
            criteria.add(Criteria.where("name").is(containsIgnoringCase));
        }
        if (hasText(category)) {
            criteria.add(Criteria.where("category").is(category.trim()));
        }

        var query = new Query();
        if (!criteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteria));
        }
        return query;
    }

    private static String escapeRegexLiteral(String value) {
        var escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ("\\.^$|?*+()[]{}-/#".indexOf(character) >= 0) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
