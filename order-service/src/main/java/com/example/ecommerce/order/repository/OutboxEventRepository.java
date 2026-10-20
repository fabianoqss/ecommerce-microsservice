package com.example.ecommerce.order.repository;

import com.example.ecommerce.order.domain.OutboxEvent;
import com.example.ecommerce.order.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
