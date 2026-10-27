package com.example.ecommerce.inventory.repository;

import com.example.ecommerce.inventory.domain.StockReservationItem;
import com.example.ecommerce.inventory.domain.StockReservationItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StockReservationItemRepository extends JpaRepository<StockReservationItem, StockReservationItemId> {
    @Modifying
    @Query(value = """
            INSERT INTO tb_stock_reservation_item (reservation_id, sku_code, quantity)
            VALUES (:reservationId, :skuCode, :quantity)
            """, nativeQuery = true)
    void insertItem(@Param("reservationId") UUID reservationId,
                    @Param("skuCode") String skuCode,
                    @Param("quantity") Integer quantity);

    List<StockReservationItem> findByIdReservationIdOrderByIdSkuCodeAsc(UUID reservationId);
}
