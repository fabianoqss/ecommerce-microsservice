package com.example.ecommerce.order.repository;


import com.example.ecommerce.order.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository <Order, UUID> {
    Page<Order> findByUserID(String userID, Pageable pageable);

    Optional<Order> findByIdAndUserID(UUID id, String userID);
}
