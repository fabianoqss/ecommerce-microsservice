package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.client.InventoryClient;
import com.example.ecommerce.order_service.client.ProductClient;
import com.example.ecommerce.order_service.dtos.OrderEventDTO;
import com.example.ecommerce.order_service.dtos.request.InventoryItemDTO;
import com.example.ecommerce.order_service.dtos.request.InventoryReserveRequestDTO;
import com.example.ecommerce.order_service.dtos.request.OrderItemRequestDTO;
import com.example.ecommerce.order_service.dtos.request.OrderRequestDTO;
import com.example.ecommerce.order_service.dtos.response.OrderItemResponseDTO;
import com.example.ecommerce.order_service.dtos.response.OrderResponseDTO;
import com.example.ecommerce.order_service.dtos.response.ProductDTO;
import com.example.ecommerce.order_service.entities.Order;
import com.example.ecommerce.order_service.entities.OrderItem;
import com.example.ecommerce.order_service.entities.OutboxEvent;
import com.example.ecommerce.order_service.enums.OrderStatus;
import com.example.ecommerce.order_service.exceptions.InsufficientStockException;
import com.example.ecommerce.order_service.exceptions.OrderAccessDeniedException;
import com.example.ecommerce.order_service.exceptions.OrderNotFoundException;
import com.example.ecommerce.order_service.exceptions.ProductNotFoundException;
import com.example.ecommerce.order_service.repositories.OrderRepository;
import com.example.ecommerce.order_service.repositories.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductClient productClient;

    @Autowired
    private InventoryClient inventoryClient;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public List<OrderResponseDTO> findAll(Authentication authentication) {
        List<Order> orders = isAdmin(authentication)
                ? orderRepository.findAll()
                : orderRepository.findByUserID(authentication.getName());

        return orders.stream()
                .map(this::toResponseDTO)
                .toList();
    }

    public OrderResponseDTO findById(Long id, Authentication authentication) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        if (!isAdmin(authentication) && !order.getUserID().equals(authentication.getName())) {
            throw new OrderAccessDeniedException();
        }

        return toResponseDTO(order);
    }

    @Transactional
    public OrderResponseDTO createOrder(OrderRequestDTO request, String userEmail) {
        List<String> productIds = request.items().stream()
                .map(OrderItemRequestDTO::productId)
                .distinct()
                .toList();

        // Busca detalhes de cada produto (uma chamada por produto único)
        Map<String, ProductDTO> productMap = productIds.stream()
                .collect(Collectors.toMap(id -> id, this::fetchProduct));

        // Reserva (baixa atômica) o estoque de todos os itens antes de persistir o pedido.
        // O inventory-service faz UPDATE condicional (quantity >= qty) por item, tudo dentro
        // de uma única transação lá: se um item não tiver estoque, os decrementos anteriores
        // daquele mesmo lote são desfeitos automaticamente (rollback), então é tudo-ou-nada.
        List<InventoryItemDTO> reserveItems = request.items().stream()
                .map(i -> new InventoryItemDTO(i.productId(), i.quantity()))
                .toList();

        try {
            inventoryClient.reserve(new InventoryReserveRequestDTO(reserveItems));
        } catch (FeignException e) {
            throw new InsufficientStockException("Estoque insuficiente para um ou mais itens do pedido");
        }

        try {
            List<OrderItem> orderItems = request.items().stream().map(itemDto -> {
                ProductDTO product = productMap.get(itemDto.productId());
                OrderItem item = new OrderItem();
                item.setProductId(itemDto.productId());
                item.setProductName(product.name());
                item.setQuantity(itemDto.quantity());
                item.setPrice(product.price());
                return item;
            }).toList();

            BigDecimal totalValue = orderItems.stream()
                    .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Não há payment-service: o estoque já foi garantido de forma síncrona acima,
            // então o pedido já nasce confirmado.
            Order order = new Order();
            order.setUserID(userEmail);
            order.setOrderTime(LocalDateTime.now());
            order.setStatus(OrderStatus.CONFIRMED);
            order.setTotalValue(totalValue);
            order.setItems(orderItems);
            order = orderRepository.save(order);

            UUID eventId = UUID.randomUUID();
            OrderEventDTO event = new OrderEventDTO(
                    eventId,
                    1,
                    order.getId(),
                    order.getUserID(),
                    order.getStatus().name(),
                    order.getTotalValue(),
                    order.getOrderTime()
            );
            outboxEventRepository.save(OutboxEvent.pending(
                    eventId,
                    order.getId(),
                    "ORDER_CREATED",
                    serializeEvent(event)
            ));

            List<OrderItemResponseDTO> itemsResponse = order.getItems().stream()
                    .map(item -> new OrderItemResponseDTO(
                            item.getProductId(),
                            item.getProductName(),
                            item.getQuantity(),
                            item.getPrice()))
                    .toList();

            return new OrderResponseDTO(
                    order.getId(),
                    order.getStatus().name(),
                    order.getTotalValue(),
                    order.getOrderTime(),
                    itemsResponse);
        } catch (RuntimeException ex) {
            // O reserve() acima já commitou numa transação remota (inventory-service);
            // o rollback local desta transação não desfaz isso, então compensamos manualmente.
            try {
                inventoryClient.release(new InventoryReserveRequestDTO(reserveItems));
            } catch (Exception releaseEx) {
                log.error("Falha ao compensar (liberar) estoque após erro ao persistir o pedido", releaseEx);
            }
            throw ex;
        }
    }

    private String serializeEvent(OrderEventDTO event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Não foi possível serializar o evento do pedido", ex);
        }
    }

    private ProductDTO fetchProduct(String productId) {
        try {
            return productClient.getProductById(productId);
        } catch (FeignException.NotFound e) {
            throw new ProductNotFoundException(productId);
        }
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }

    private OrderResponseDTO toResponseDTO(Order order) {
        List<OrderItemResponseDTO> items = order.getItems().stream()
                .map(item -> new OrderItemResponseDTO(
                        item.getProductId(),
                        item.getProductName(),
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
