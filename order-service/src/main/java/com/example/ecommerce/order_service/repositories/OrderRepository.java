package com.example.ecommerce.order_service.repositories;


import com.example.ecommerce.order_service.entities.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository <Order, Long> {
    List<Order> findByUserID(String userID);
}
