package com.example.ecommerce.inventory_service.services;


import com.example.ecommerce.inventory_service.dto.InventoryDTO;
import com.example.ecommerce.inventory_service.dto.InventoryRequestDTO;
import com.example.ecommerce.inventory_service.dto.InventoryUpdateDTO;
import com.example.ecommerce.inventory_service.entities.Inventory;
import com.example.ecommerce.inventory_service.repositories.InventoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InventoryService {

    @Autowired
    private InventoryRepository inventoryRepository;

    public List<InventoryDTO> isInStock(List<String> skuCode){
        List<Inventory> lista = inventoryRepository.findBySkuCodeIn(skuCode);
        return lista.stream().map(x -> new InventoryDTO(x)).toList();
    }

    public InventoryDTO insert(InventoryRequestDTO dto){
        Inventory inventory = new Inventory();
        inventory.setSkuCode(dto.skuCode());
        inventory.setQuantity(dto.quantity());
        inventory = inventoryRepository.save(inventory);
        return new InventoryDTO(inventory);
    }

    public InventoryDTO update(Long id, InventoryUpdateDTO dto){
        Inventory inventory = inventoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item de estoque não encontrado com id: " + id));
        inventory.setQuantity(dto.quantity());
        inventory = inventoryRepository.save(inventory);
        return new InventoryDTO(inventory);
    }
}
