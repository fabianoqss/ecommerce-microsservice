package com.example.ecommerce.inventory.service;


import com.example.ecommerce.inventory.dto.response.InventoryDTO;
import com.example.ecommerce.inventory.dto.request.InventoryItemDTO;
import com.example.ecommerce.inventory.dto.request.InventoryRequestDTO;
import com.example.ecommerce.inventory.dto.request.InventoryUpdateDTO;
import com.example.ecommerce.inventory.domain.Inventory;
import com.example.ecommerce.inventory.exception.InsufficientStockException;
import com.example.ecommerce.inventory.exception.InventoryNotFoundException;
import com.example.ecommerce.inventory.repository.InventoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .orElseThrow(() -> new InventoryNotFoundException(id));
        inventory.setQuantity(dto.quantity());
        inventory = inventoryRepository.save(inventory);
        return new InventoryDTO(inventory);
    }

    @Transactional
    public void reserve(List<InventoryItemDTO> items) {
        for (InventoryItemDTO item : items) {
            int updated = inventoryRepository.decrement(item.skuCode(), item.quantity());
            if (updated == 0) {
                throw new InsufficientStockException(item.skuCode());
            }
        }
    }

    @Transactional
    public void release(List<InventoryItemDTO> items) {
        for (InventoryItemDTO item : items) {
            inventoryRepository.increment(item.skuCode(), item.quantity());
        }
    }
}
