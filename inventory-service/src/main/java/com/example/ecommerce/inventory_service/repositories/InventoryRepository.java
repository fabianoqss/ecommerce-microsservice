package com.example.ecommerce.inventory_service.repositories;

import com.example.ecommerce.inventory_service.entities.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryRepository extends JpaRepository <Inventory, Long> {

    List<Inventory> findBySkuCodeIn(List<String> skuCode);

}
