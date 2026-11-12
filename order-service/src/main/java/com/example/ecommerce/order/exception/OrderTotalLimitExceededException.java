package com.example.ecommerce.order.exception;

import java.math.BigDecimal;

public class OrderTotalLimitExceededException extends RuntimeException {
    public OrderTotalLimitExceededException(BigDecimal limit) {
        super("Order total exceeds the limit of " + limit.toPlainString() + " per order");
    }
}
