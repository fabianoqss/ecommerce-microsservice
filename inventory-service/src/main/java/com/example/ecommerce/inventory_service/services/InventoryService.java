package com.example.ecommerce.inventory_service.services;


import com.example.ecommerce.inventory_service.dto.InventoryDTO;
import com.example.ecommerce.inventory_service.entities.Inventory;
import com.example.ecommerce.inventory_service.repositories.InventoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    @Autowired
    private InventoryRepository inventoryRepository;


    public List<InventoryDTO> isInStock(List<String> skuCode){

        List<Inventory> lista = inventoryRepository.findBySkuCodeIn(skuCode);

        return lista.stream().map(x -> new InventoryDTO(x)).toList();

    }
}
