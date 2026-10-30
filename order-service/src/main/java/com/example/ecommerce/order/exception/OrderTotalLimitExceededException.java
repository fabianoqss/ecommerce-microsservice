package com.example.ecommerce.order.exception;

import java.math.BigDecimal;

public class OrderTotalLimitExceededException extends RuntimeException {
    public OrderTotalLimitExceededException(BigDecimal limit) {
        super("O valor total do pedido excede o limite de " + limit.toPlainString() + " por pedido");
    }
}
