package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.dtos.OrderRequestDTO;
import com.example.ecommerce.order_service.dtos.OrderResponseDTO;
import com.example.ecommerce.order_service.entities.Order;
import com.example.ecommerce.order_service.entities.OrderItem;
import com.example.ecommerce.order_service.enums.OrderStatus;
import com.example.ecommerce.order_service.repositories.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service

public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    public OrderResponseDTO createOrder(OrderRequestDTO orderRequestDTO){



        return null;
    }

/*
    private Order copyDTOtoEntity(Order order ,OrderRequestDTO request){
        order = new Order();
        order.setUserID(request.userId());
        order.setOrderTime(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING);

        List<OrderItem> items = request.items().stream().map(itemDto -> {
            OrderItem item = new OrderItem();
            item.setProductId(itemDto.productId());
            item.setQuantity(itemDto.quantity());


        };



        return order;
    }

*/

}
