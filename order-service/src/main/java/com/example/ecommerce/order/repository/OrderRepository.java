package com.example.ecommerce.order.repository;

import com.example.ecommerce.order.domain.Order;
import com.example.ecommerce.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Page<Order> findByUserID(String userID, Pageable pageable);

    // "Meus pedidos" oculta tentativas que nunca chegaram a confirmar (confirmed_at IS NULL).
    Page<Order> findByUserIDAndConfirmedAtIsNotNull(String userID, Pageable pageable);

    Optional<Order> findByIdAndUserID(UUID id, String userID);

    long countByStatusIn(Collection<OrderStatus> statuses);

    // SKIP LOCKED permite múltiplas instâncias do reconciliador sem disputar os mesmos pedidos.
    // Inclui EXPIRED para retentar a liberação caso o DELETE no inventory tenha falhado na rodada anterior.
    @Query(value = """
            SELECT * FROM tb_orders
            WHERE (status = 'PENDING' AND order_time < :cutoff)
               OR status = 'EXPIRED'
            ORDER BY order_time ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Order> findReconcilableOrdersForUpdate(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    Optional<Order> findByUserIDAndIdempotencyKey(String userID, UUID idempotencyKey);
}
