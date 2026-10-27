package com.example.ecommerce.inventory.repository;

import com.example.ecommerce.inventory.domain.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO tb_stock_reservation (id, status, created_at, updated_at)
            VALUES (:id, :status, :now, :now)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("status") String status, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE tb_stock_reservation
            SET status = 'RELEASED', updated_at = :now
            WHERE id = :id AND status = 'RESERVED'
            """, nativeQuery = true)
    int transitionReservedToReleased(@Param("id") UUID id, @Param("now") Instant now);
}
