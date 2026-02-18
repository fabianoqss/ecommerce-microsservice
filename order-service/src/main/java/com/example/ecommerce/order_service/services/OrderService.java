package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.dtos.OrderRequestDTO;
import com.example.ecommerce.order_service.dtos.OrderResponseDTO;
import com.example.ecommerce.order_service.repositories.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service

public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    public OrderResponseDTO createOrder(OrderRequestDTO orderRequestDTO){

        return null;
    }



    


}
