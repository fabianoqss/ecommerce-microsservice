package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.client.InventoryClient;
import com.example.ecommerce.order_service.client.ProductClient;
import com.example.ecommerce.order_service.config.RabbitMQConfig;
import com.example.ecommerce.order_service.dtos.OrderEventDTO;
import com.example.ecommerce.order_service.dtos.request.OrderItemRequestDTO;
import com.example.ecommerce.order_service.dtos.request.OrderRequestDTO;
import com.example.ecommerce.order_service.dtos.response.InventoryResponseDTO;
import com.example.ecommerce.order_service.dtos.response.OrderItemResponseDTO;
import com.example.ecommerce.order_service.dtos.response.OrderResponseDTO;
import com.example.ecommerce.order_service.dtos.response.ProductDTO;
import com.example.ecommerce.order_service.entities.Order;
import com.example.ecommerce.order_service.entities.OrderItem;
import com.example.ecommerce.order_service.enums.OrderStatus;
import com.example.ecommerce.order_service.repositories.OrderRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductClient productClient;

    @Autowired
    private InventoryClient inventoryClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    public List<OrderResponseDTO> findAll() {
        return orderRepository.findAll().stream()
                .map(this::toResponseDTO)
                .toList();
    }

    public OrderResponseDTO findById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Pedido não encontrado com id: " + id));
        return toResponseDTO(order);
    }

    @Transactional
    public OrderResponseDTO createOrder(OrderRequestDTO request) {
        List<String> productIds = request.items().stream()
                .map(OrderItemRequestDTO::productId)
                .toList();

        // Verifica estoque de todos os itens de uma vez
        List<InventoryResponseDTO> stockList = inventoryClient.isInStock(productIds);

        for (OrderItemRequestDTO itemRequest : request.items()) {
            InventoryResponseDTO stock = stockList.stream()
                    .filter(s -> s.skuCode().equals(itemRequest.productId()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                            "Produto não encontrado no estoque: " + itemRequest.productId()));

            if (stock.quantity() < itemRequest.quantity()) {
                throw new RuntimeException(
                        "Estoque insuficiente para o produto: " + itemRequest.productId()
                        + ". Disponível: " + stock.quantity() + ", Solicitado: " + itemRequest.quantity());
            }
        }

        // Busca detalhes de cada produto (uma chamada por produto único)
        Map<String, ProductDTO> productMap = productIds.stream()
                .distinct()
                .collect(Collectors.toMap(id -> id, productClient::getProductById));

        // Monta os itens do pedido
        List<OrderItem> orderItems = request.items().stream().map(itemDto -> {
            ProductDTO product = productMap.get(itemDto.productId());
            OrderItem item = new OrderItem();
            item.setProductId(itemDto.productId());
            item.setQuantity(itemDto.quantity());
            item.setPrice(product.price());
            return item;
        }).toList();

        // Calcula o valor total
        BigDecimal totalValue = orderItems.stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Salva o pedido
        Order order = new Order();
        order.setUserID(request.userId());
        order.setOrderTime(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING);
        order.setTotalValue(totalValue);
        order.setItems(orderItems);
        order = orderRepository.save(order);

        OrderEventDTO event = new OrderEventDTO(
                order.getId(),
                order.getUserID(),
                order.getStatus().name(),
                order.getTotalValue(),
                order.getOrderTime()
        );
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.ROUTING_KEY,
                event
        );

        // Monta o response
        List<OrderItemResponseDTO> itemsResponse = order.getItems().stream()
                .map(item -> new OrderItemResponseDTO(
                        item.getProductId(),
                        productMap.get(item.getProductId()).name(),
                        item.getQuantity(),
                        item.getPrice()))
                .toList();

        return new OrderResponseDTO(
                order.getId(),
                order.getStatus().name(),
                order.getTotalValue(),
                order.getOrderTime(),
                itemsResponse);
    }

    private OrderResponseDTO toResponseDTO(Order order) {
        List<OrderItemResponseDTO> items = order.getItems().stream()
                .map(item -> new OrderItemResponseDTO(
                        item.getProductId(),
                        null,
                        item.getQuantity(),
                        item.getPrice()))
                .toList();

        return new OrderResponseDTO(
                order.getId(),
                order.getStatus().name(),
                order.getTotalValue(),
                order.getOrderTime(),
                items);
    }
}
