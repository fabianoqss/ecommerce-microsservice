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
import com.example.ecommerce.order_service.dtos.response.PageResponseDTO;
import com.example.ecommerce.order_service.dtos.response.ProductDTO;
import com.example.ecommerce.order_service.entities.Order;
import com.example.ecommerce.order_service.entities.OrderItem;
import com.example.ecommerce.order_service.entities.OutboxEvent;
import com.example.ecommerce.order_service.enums.OrderEventType;
import com.example.ecommerce.order_service.enums.OrderStatus;
import com.example.ecommerce.order_service.exceptions.InsufficientStockException;
import com.example.ecommerce.order_service.exceptions.InvalidProductDataException;
import com.example.ecommerce.order_service.exceptions.OrderNotFoundException;
import com.example.ecommerce.order_service.exceptions.ProductNotFoundException;
import com.example.ecommerce.order_service.repositories.OrderRepository;
import com.example.ecommerce.order_service.repositories.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderService {

    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "orderTime", "id");
    // v2: orderId passou de número (Long) para UUID. Consumidores aceitam as duas versões.
    private static final int ORDER_EVENT_VERSION = 2;

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

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Transactional(readOnly = true)
    public PageResponseDTO<OrderResponseDTO> findAll(int page, int size, Authentication authentication) {
        // Ordenação fixa no servidor: o cliente não escolhe propriedade de sort (evita erro/sort em coluna sem índice).
        Pageable pageable = PageRequest.of(page, size, LATEST_FIRST);
        Page<Order> orders = isAdmin(authentication)
                ? orderRepository.findAll(pageable)
                : orderRepository.findByUserID(authentication.getName(), pageable);

        return PageResponseDTO.from(orders.map(this::toResponseDTO));
    }

    @Transactional(readOnly = true)
    public OrderResponseDTO findById(UUID id, Authentication authentication) {
        Optional<Order> order = isAdmin(authentication)
                ? orderRepository.findById(id)
                : orderRepository.findByIdAndUserID(id, authentication.getName());

        // Pedido de outro usuário responde 404, igual a um inexistente: um 403 revelaria quais IDs
        // existem e permitiria enumerar pedidos (IDOR).
        return order.map(this::toResponseDTO)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public OrderResponseDTO createOrder(OrderRequestDTO request, String userId) {
        List<String> productIds = request.items().stream()
                .map(OrderItemRequestDTO::productId)
                .distinct()
                .toList();

        Map<String, ProductDTO> productMap = productIds.stream()
                .collect(Collectors.toMap(id -> id, this::fetchProduct));

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

        Order order = new Order();
        order.setUserID(userId);
        order.setOrderTime(LocalDateTime.now());
        order.setStatus(OrderStatus.CONFIRMED);
        order.setTotalValue(totalValue);
        order.setItems(orderItems);

        List<InventoryItemDTO> reserveItems = aggregateInventoryItems(request.items().stream()
                .map(i -> new InventoryItemDTO(i.productId(), i.quantity()))
                .toList());

        try {
            inventoryClient.reserve(new InventoryReserveRequestDTO(reserveItems));
        } catch (FeignException.Conflict e) {
            // Só o 409 do inventory significa falta de estoque. 401/403 (token de serviço rejeitado)
            // e demais falhas seguem para o GlobalExceptionHandler, que responde 502.
            throw new InsufficientStockException("Estoque insuficiente para um ou mais itens do pedido");
        }

        Order savedOrder;
        try {
            savedOrder = transactionTemplate.execute(status -> {
                Order persistedOrder = orderRepository.saveAndFlush(order);
                enqueueEvent(persistedOrder, OrderEventType.ORDER_CREATED);
                return persistedOrder;
            });
        } catch (RuntimeException ex) {
            // execute() inclui o commit; compensamos só após encerrar a transação local.
            try {
                inventoryClient.release(new InventoryReserveRequestDTO(reserveItems));
            } catch (Exception releaseEx) {
                log.error("Falha ao compensar (liberar) estoque após erro ao persistir o pedido", releaseEx);
            }
            throw ex;
        }

        return toResponseDTO(savedOrder);
    }

    /**
     * Cancela o pedido (dono ou admin) e devolve o estoque reservado. Repetir o cancelamento de um
     * pedido já cancelado é idempotente: responde o estado atual sem devolver estoque de novo.
     */
    @Transactional
    public OrderResponseDTO cancel(UUID id, String reason, Authentication authentication) {
        Optional<Order> found = isAdmin(authentication)
                ? orderRepository.findById(id)
                : orderRepository.findByIdAndUserID(id, authentication.getName());
        // Mesmo critério do findById: pedido de outro usuário responde 404.
        Order order = found.orElseThrow(() -> new OrderNotFoundException(id));

        if (!order.cancel(authentication.getName(), reason, LocalDateTime.now())) {
            return toResponseDTO(order);
        }

        // Grava (e confere o @Version) ANTES de chamar o inventory: um cancelamento concorrente falha
        // aqui com 409 sem devolver o estoque duas vezes, e a linha fica bloqueada até o commit.
        orderRepository.saveAndFlush(order);

        // Se a devolução falhar, a exceção desfaz a transação local: o pedido continua CONFIRMED e
        // o cliente pode tentar de novo (502/503 pelo GlobalExceptionHandler).
        inventoryClient.release(new InventoryReserveRequestDTO(toInventoryItems(order)));

        enqueueEvent(order, OrderEventType.ORDER_CANCELLED);

        String actor = Objects.equals(order.getUserID(), authentication.getName()) ? "owner" : "admin";
        afterCommit(() -> meterRegistry.counter("orders.cancelled", "actor", actor).increment());
        log.info("Pedido cancelado: orderId={}, actor={}, cancelledBy={}", order.getId(), actor, order.getCancelledBy());

        return toResponseDTO(order);
    }

    private void enqueueEvent(Order order, OrderEventType type) {
        UUID eventId = UUID.randomUUID();
        OrderEventDTO event = new OrderEventDTO(
                eventId,
                ORDER_EVENT_VERSION,
                order.getId(),
                order.getUserID(),
                order.getStatus().name(),
                order.getTotalValue(),
                order.getOrderTime()
        );
        outboxEventRepository.save(OutboxEvent.pending(eventId, order.getId(), type.name(), serializeEvent(event)));
    }

    private static List<InventoryItemDTO> toInventoryItems(Order order) {
        return aggregateInventoryItems(order.getItems().stream()
                .map(item -> new InventoryItemDTO(item.getProductId(), item.getQuantity()))
                .toList());
    }

    private static List<InventoryItemDTO> aggregateInventoryItems(List<InventoryItemDTO> items) {
        // A mesma ordem de bloqueio por SKU evita deadlocks no inventory-service.
        Map<String, Integer> quantities = new TreeMap<>();
        items.forEach(item -> quantities.merge(item.skuCode(), item.quantity(), Math::addExact));
        return quantities.entrySet().stream()
                .map(entry -> new InventoryItemDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    // Métrica de negócio só conta o que de fato foi persistido (não conta rollback).
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
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
            ProductDTO product = productClient.getProductById(productId);
            if (product == null || product.price() == null || product.name() == null || product.name().isBlank()) {
                throw new InvalidProductDataException(productId);
            }
            return product;
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
