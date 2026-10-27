package com.example.ecommerce.order.service;

import com.example.ecommerce.order.domain.entity.Order;
import com.example.ecommerce.order.domain.entity.OrderItem;
import com.example.ecommerce.order.domain.entity.OutboxEvent;
import com.example.ecommerce.order.domain.enums.OrderEventType;
import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.domain.enums.ReservationMode;
import com.example.ecommerce.order.dto.client.inventory.InventoryItemDTO;
import com.example.ecommerce.order.dto.client.inventory.InventoryReserveRequestDTO;
import com.example.ecommerce.order.dto.client.product.ProductDTO;
import com.example.ecommerce.order.dto.event.OrderEventDTO;
import com.example.ecommerce.order.dto.request.OrderItemRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.dto.response.CreateOrderResult;
import com.example.ecommerce.order.dto.response.OrderItemResponseDTO;
import com.example.ecommerce.order.dto.response.OrderResponseDTO;
import com.example.ecommerce.order.dto.response.PageResponseDTO;
import com.example.ecommerce.order.exception.IdempotencyKeyReuseException;
import com.example.ecommerce.order.exception.InsufficientStockException;
import com.example.ecommerce.order.exception.InvalidOrderStatusTransitionException;
import com.example.ecommerce.order.exception.InvalidProductDataException;
import com.example.ecommerce.order.exception.OrderInProgressException;
import com.example.ecommerce.order.exception.OrderNotFoundException;
import com.example.ecommerce.order.exception.OrderReservationException;
import com.example.ecommerce.order.exception.ProductNotFoundException;
import com.example.ecommerce.order.infra.client.InventoryClient;
import com.example.ecommerce.order.infra.client.ProductClient;
import com.example.ecommerce.order.infra.tracing.OutboxTraceContext;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.repository.OutboxEventRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.RetryableException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "orderTime", "id");
    private static final int ORDER_EVENT_VERSION = 2;
    private static final int ORDER_CANCELLED_EVENT_VERSION = 3;

    private final OrderRepository orderRepository;

    private final ProductClient productClient;

    private final InventoryClient inventoryClient;

    private final OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper;

    private final MeterRegistry meterRegistry;

    private final OutboxTraceContext outboxTraceContext;

    private final TransactionTemplate transactionTemplate;

    @Setter
    @Value("${ecommerce.order.reservation.max-attempts:3}")
    private int reservationMaxAttempts = 3;

    @Setter
    @Value("${ecommerce.order.reservation.retry-delay:50ms}")
    private Duration reservationRetryDelay = Duration.ofMillis(50);

    @Transactional(readOnly = true)
    public PageResponseDTO<OrderResponseDTO> findAll(int page, int size, Authentication authentication) {
        Pageable pageable = PageRequest.of(page, size, LATEST_FIRST);
        Page<Order> orders = isAdmin(authentication)
                ? orderRepository.findAll(pageable)
                : orderRepository.findByUserIDAndConfirmedAtIsNotNull(authentication.getName(), pageable);

        return PageResponseDTO.from(orders.map(this::toResponseDTO));
    }

    @Transactional(readOnly = true)
    public OrderResponseDTO findById(UUID id, Authentication authentication) {
        Optional<Order> order = isAdmin(authentication)
                ? orderRepository.findById(id)
                : orderRepository.findByIdAndUserID(id, authentication.getName());

        return order.map(this::toResponseDTO)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public CreateOrderResult createOrder(OrderRequestDTO request, String userId, UUID idempotencyKey) {
        List<InventoryItemDTO> reserveItems = aggregateInventoryItems(request.items().stream()
                .map(i -> new InventoryItemDTO(i.productId(), i.quantity()))
                .toList());
        String requestHash = hashOf(reserveItems);

        Optional<CreateOrderResult> replay = findReplay(userId, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

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
        order.setIdempotencyKey(idempotencyKey);
        order.setRequestHash(requestHash);
        order.setOrderTime(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING);
        order.setReservationMode(ReservationMode.LEDGER);
        order.setTotalValue(totalValue);
        order.setItems(orderItems);

        Order pendingOrder;
        try {
            pendingOrder = Objects.requireNonNull(
                    transactionTemplate.execute(status -> orderRepository.saveAndFlush(order)),
                    "Falha ao persistir o pedido pendente");
        } catch (DataIntegrityViolationException e) {
            meterRegistry.counter("orders.idempotency", "result", "race").increment();
            return findReplay(userId, idempotencyKey, requestHash).orElseThrow(() -> e);
        }
        UUID orderId = pendingOrder.getId();

        try {
            reserveWithRetry(orderId, new InventoryReserveRequestDTO(reserveItems));
        } catch (FeignException.Conflict e) {
            cancelOrderWithoutStock(pendingOrder);
            meterRegistry.counter("orders.created", "result", "insufficient_stock").increment();
            log.info("Pedido cancelado por falta de estoque: orderId={}", orderId);
            throw new InsufficientStockException("Estoque insuficiente para um ou mais itens do pedido");
        } catch (OAuth2AuthorizationException e) {
            meterRegistry.counter("orders.created", "result", "pending").increment();
            log.warn("Pedido mantido PENDING por indisponibilidade do token de serviço: orderId={}", orderId);
            throw e;
        } catch (RuntimeException e) {
            meterRegistry.counter("orders.created", "result", "pending").increment();
            log.warn("Falha ao reservar estoque; pedido permanecerá PENDING para reconciliação: orderId={}", orderId, e);
            throw new OrderReservationException(
                    "Não foi possível confirmar a reserva de estoque do pedido " + orderId + "; consulte o status do pedido", e);
        }

        try {
            OrderResponseDTO response = transactionTemplate.execute(status -> {
                pendingOrder.confirm(Instant.now());
                Order confirmedOrder = orderRepository.saveAndFlush(pendingOrder);
                enqueueEvent(confirmedOrder, OrderEventType.ORDER_CREATED);
                return toResponseDTO(confirmedOrder);
            });
            meterRegistry.counter("orders.created", "result", "confirmed").increment();
            log.info("Pedido confirmado com sucesso: orderId={}", orderId);
            return new CreateOrderResult(response, false);
        } catch (OptimisticLockingFailureException | InvalidOrderStatusTransitionException ex) {
            meterRegistry.counter("orders.created", "result", "pending").increment();
            log.warn("Confirmação rejeitada porque o reconciliador já expirou o pedido: orderId={}", orderId, ex);
            throw new OrderReservationException(
                    "O tempo limite de confirmação do pedido " + orderId + " expirou", ex);
        }
    }

    private void reserveWithRetry(UUID orderId, InventoryReserveRequestDTO request) {
        int maxAttempts = Math.max(1, reservationMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                inventoryClient.reserve(orderId, request);
                return;
            } catch (RuntimeException ex) {
                if (attempt >= maxAttempts || !isRetryableReserveFailure(ex)) {
                    throw ex;
                }
                log.warn("Falha transitória ao reservar estoque (tentativa {}/{}): orderId={}",
                        attempt, maxAttempts, orderId, ex);
                sleepBeforeRetry();
            }
        }
    }

    private static boolean isRetryableReserveFailure(Throwable ex) {
        if (ex instanceof RetryableException) {
            return true;
        }
        if (ex instanceof FeignException feignEx) {
            return feignEx.status() >= 500 && feignEx.status() < 600;
        }
        return ex.getCause() instanceof IOException;
    }

    private void sleepBeforeRetry() {
        long delayMillis = reservationRetryDelay != null ? reservationRetryDelay.toMillis() : 0L;
        if (delayMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void cancelOrderWithoutStock(Order pendingOrder) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                pendingOrder.cancel("system", "Estoque insuficiente", LocalDateTime.now());
                orderRepository.saveAndFlush(pendingOrder);
            });
        } catch (OptimisticLockingFailureException ex) {
            log.debug("Pedido já alterado concorrentemente pelo reconciliador ao cancelar por falta de estoque: orderId={}",
                    pendingOrder.getId(), ex);
        }
    }

    @Transactional
    public OrderResponseDTO cancel(UUID id, String reason, Authentication authentication) {
        Optional<Order> found = isAdmin(authentication)
                ? orderRepository.findById(id)
                : orderRepository.findByIdAndUserID(id, authentication.getName());
        Order order = found.orElseThrow(() -> new OrderNotFoundException(id));

        if (!order.cancel(authentication.getName(), reason, LocalDateTime.now())) {
            return toResponseDTO(order);
        }

        orderRepository.saveAndFlush(order);
        enqueueEvent(order, OrderEventType.ORDER_CANCELLED);

        String actor = Objects.equals(order.getUserID(), authentication.getName()) ? "owner" : "admin";
        afterCommit(() -> meterRegistry.counter("orders.cancelled", "actor", actor).increment());
        log.info("Pedido cancelado: orderId={}, actor={}, cancelledBy={}", order.getId(), actor, order.getCancelledBy());

        return toResponseDTO(order);
    }

    private void enqueueEvent(Order order, OrderEventType type) {
        UUID eventId = UUID.randomUUID();
        boolean cancelled = type == OrderEventType.ORDER_CANCELLED;
        OrderEventDTO event = new OrderEventDTO(
                eventId,
                cancelled ? ORDER_CANCELLED_EVENT_VERSION : ORDER_EVENT_VERSION,
                order.getId(),
                order.getUserID(),
                order.getStatus().name(),
                order.getTotalValue(),
                order.getOrderTime(),
                cancelled ? order.getReservationMode() : null,
                cancelled ? toInventoryItems(order) : null
        );
        OutboxEvent outbox = OutboxEvent.pending(eventId, order.getId(), type.name(), serializeEvent(event));
        outbox.setTraceparent(outboxTraceContext.capture());
        outboxEventRepository.save(outbox);
    }

    private static List<InventoryItemDTO> toInventoryItems(Order order) {
        return aggregateInventoryItems(order.getItems().stream()
                .map(item -> new InventoryItemDTO(item.getProductId(), item.getQuantity()))
                .toList());
    }

    private Optional<CreateOrderResult> findReplay(String userId, UUID idempotencyKey, String requestHash) {
        return transactionTemplate.execute(status -> orderRepository
                .findByUserIDAndIdempotencyKey(userId, idempotencyKey)
                .map(existing -> toReplay(existing, requestHash)));
    }

    private CreateOrderResult toReplay(Order existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            meterRegistry.counter("orders.idempotency", "result", "payload_mismatch").increment();
            throw new IdempotencyKeyReuseException("A chave de idempotência já foi usada com outro conteúdo de pedido");
        }
        if (existing.getStatus() == OrderStatus.PENDING) {
            meterRegistry.counter("orders.idempotency", "result", "in_progress").increment();
            throw new OrderInProgressException(existing.getId());
        }
        meterRegistry.counter("orders.idempotency", "result", "replayed").increment();
        log.info("Requisição repetida devolvida pela chave de idempotência: orderId={}", existing.getId());
        return new CreateOrderResult(toResponseDTO(existing), true);
    }

    private static String hashOf(List<InventoryItemDTO> items) {
        String canonical = items.stream()
                .map(item -> item.skuCode() + ":" + item.quantity())
                .collect(Collectors.joining(";"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    private static List<InventoryItemDTO> aggregateInventoryItems(List<InventoryItemDTO> items) {
        Map<String, Integer> quantities = new TreeMap<>();
        items.forEach(item -> quantities.merge(item.skuCode(), item.quantity(), Math::addExact));
        return quantities.entrySet().stream()
                .map(entry -> new InventoryItemDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

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
        } catch (JacksonException ex) {
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
