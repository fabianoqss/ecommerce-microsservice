package com.example.ecommerce.order_service.repositories;

import com.example.ecommerce.order_service.entities.OutboxEvent;
import com.example.ecommerce.order_service.entities.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
