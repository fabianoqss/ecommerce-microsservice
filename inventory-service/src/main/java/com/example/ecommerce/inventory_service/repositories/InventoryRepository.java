package com.example.ecommerce.inventory_service.repositories;

import com.example.ecommerce.inventory_service.entities.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InventoryRepository extends JpaRepository <Inventory, Long> {

    List<Inventory> findBySkuCodeIn(List<String> skuCode);

    @Modifying
    @Query("UPDATE Inventory i SET i.quantity = i.quantity - :qty WHERE i.skuCode = :sku AND i.quantity >= :qty")
    int decrement(@Param("sku") String skuCode, @Param("qty") Integer quantity);

    @Modifying
    @Query("UPDATE Inventory i SET i.quantity = i.quantity + :qty WHERE i.skuCode = :sku")
    int increment(@Param("sku") String skuCode, @Param("qty") Integer quantity);

}
