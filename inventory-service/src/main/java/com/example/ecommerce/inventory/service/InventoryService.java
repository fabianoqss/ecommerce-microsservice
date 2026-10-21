package com.example.ecommerce.inventory.service;

import com.example.ecommerce.inventory.domain.Inventory;
import com.example.ecommerce.inventory.domain.ReservationStatus;
import com.example.ecommerce.inventory.domain.StockReservation;
import com.example.ecommerce.inventory.domain.StockReservationItem;
import com.example.ecommerce.inventory.dto.request.InventoryItemDTO;
import com.example.ecommerce.inventory.dto.request.InventoryRequestDTO;
import com.example.ecommerce.inventory.dto.request.InventoryUpdateDTO;
import com.example.ecommerce.inventory.dto.response.InventoryDTO;
import com.example.ecommerce.inventory.dto.response.ReservationResponseDTO;
import com.example.ecommerce.inventory.dto.response.ReservationResult;
import com.example.ecommerce.inventory.exception.InsufficientStockException;
import com.example.ecommerce.inventory.exception.InventoryNotFoundException;
import com.example.ecommerce.inventory.exception.ReservationConflictException;
import com.example.ecommerce.inventory.exception.ReservationGoneException;
import com.example.ecommerce.inventory.exception.ReservationNotFoundException;
import com.example.ecommerce.inventory.repository.InventoryRepository;
import com.example.ecommerce.inventory.repository.StockReservationItemRepository;
import com.example.ecommerce.inventory.repository.StockReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final String METRIC_RESERVATIONS = "inventory.reservations";
    private static final String METRIC_RELEASES = "inventory.releases";

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private StockReservationItemRepository reservationItemRepository;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public List<InventoryDTO> isInStock(List<String> skuCode) {
        List<Inventory> lista = inventoryRepository.findBySkuCodeIn(skuCode);
        return lista.stream().map(InventoryDTO::new).toList();
    }

    public InventoryDTO insert(InventoryRequestDTO dto) {
        Inventory inventory = new Inventory();
        inventory.setSkuCode(dto.skuCode());
        inventory.setQuantity(dto.quantity());
        inventory = inventoryRepository.save(inventory);
        return new InventoryDTO(inventory);
    }

    public InventoryDTO update(Long id, InventoryUpdateDTO dto) {
        Inventory inventory = inventoryRepository.findById(id)
                .orElseThrow(() -> new InventoryNotFoundException(id));
        inventory.setQuantity(dto.quantity());
        inventory = inventoryRepository.save(inventory);
        return new InventoryDTO(inventory);
    }

    // Reserva idempotente por reservationId (orderId). Itens são somados e ordenados por SKU
    // para adquirir locks de linha em ordem determinística e evitar deadlocks.
    @Transactional
    public ReservationResult reserve(UUID reservationId, List<InventoryItemDTO> items) {
        Map<String, Integer> normalizedItems = normalizeItems(items);
        Instant now = Instant.now();

        int inserted = reservationRepository.insertIfAbsent(
                reservationId, ReservationStatus.RESERVED.name(), now);

        if (inserted == 1) {
            for (Map.Entry<String, Integer> entry : normalizedItems.entrySet()) {
                String skuCode = entry.getKey();
                Integer qty = entry.getValue();
                int updated = inventoryRepository.decrement(skuCode, qty);
                if (updated == 0) {
                    recordReservationMetric("insufficient");
                    log.warn("Estoque insuficiente ou SKU inexistente ao reservar: reservationId={}, skuCode={}, quantity={}",
                            reservationId, skuCode, qty);
                    throw new InsufficientStockException(skuCode);
                }
                reservationItemRepository.insertItem(reservationId, skuCode, qty);
            }

            List<InventoryItemDTO> itemDtos = normalizedItems.entrySet().stream()
                    .map(e -> new InventoryItemDTO(e.getKey(), e.getValue()))
                    .toList();
            recordReservationMetric("created");
            log.info("Reserva criada: reservationId={}, skus={}", reservationId, itemDtos.size());
            return new ReservationResult(
                    new ReservationResponseDTO(reservationId, ReservationStatus.RESERVED, itemDtos, now, now),
                    true
            );
        }

        // Outro request já criou a reserva ou lápide (ON CONFLICT aguardou eventual tx concorrente).
        StockReservation existing = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (existing.getStatus() == ReservationStatus.RELEASED) {
            recordReservationMetric("gone");
            log.warn("Tentativa de reservar sobre reserva já liberada/lápide: reservationId={}", reservationId);
            throw new ReservationGoneException(reservationId);
        }

        List<StockReservationItem> existingItems =
                reservationItemRepository.findByIdReservationIdOrderByIdSkuCodeAsc(reservationId);
        Map<String, Integer> existingNormalized = new TreeMap<>();
        for (StockReservationItem item : existingItems) {
            existingNormalized.put(item.getId().getSkuCode(), item.getQuantity());
        }

        if (!existingNormalized.equals(normalizedItems)) {
            recordReservationMetric("conflict");
            log.warn("Conflito de itens em reserva existente: reservationId={}", reservationId);
            throw new ReservationConflictException(reservationId);
        }

        List<InventoryItemDTO> existingDtos = existingItems.stream()
                .map(i -> new InventoryItemDTO(i.getId().getSkuCode(), i.getQuantity()))
                .toList();
        recordReservationMetric("replayed");
        log.info("Replay idempotente de reserva: reservationId={}", reservationId);
        return new ReservationResult(
                new ReservationResponseDTO(
                        existing.getId(),
                        existing.getStatus(),
                        existingDtos,
                        existing.getCreatedAt(),
                        existing.getUpdatedAt()
                ),
                false
        );
    }

    // Liberação idempotente em duas etapas para segurança sob isolamento READ COMMITTED:
    // (a) INSERT lápide ON CONFLICT DO NOTHING aguarda eventual PUT concorrente em voo;
    // (b) UPDATE em statement separado enxerga novo snapshot (commit do PUT) e garante devolução única.
    @Transactional
    public String release(UUID reservationId) {
        Instant now = Instant.now();

        int inserted = reservationRepository.insertIfAbsent(
                reservationId, ReservationStatus.RELEASED.name(), now);
        if (inserted == 1) {
            recordReleaseMetric("tombstone");
            log.info("Lápide (tombstone) de reserva criada: reservationId={}", reservationId);
            return "tombstone";
        }

        int updated = reservationRepository.transitionReservedToReleased(reservationId, now);
        if (updated == 1) {
            List<StockReservationItem> items =
                    reservationItemRepository.findByIdReservationIdOrderByIdSkuCodeAsc(reservationId);
            for (StockReservationItem item : items) {
                inventoryRepository.increment(item.getId().getSkuCode(), item.getQuantity());
            }
            recordReleaseMetric("released");
            log.info("Reserva liberada e estoque incrementado: reservationId={}, skus={}",
                    reservationId, items.size());
            return "released";
        }

        recordReleaseMetric("noop");
        log.info("Liberação idempotente ignorada (noop, já RELEASED): reservationId={}", reservationId);
        return "noop";
    }

    // Devolução idempotente para pedidos LEGACY (anteriores à V7, sem reserva no livro-razão):
    // grava uma lápide RELEASED por orderId; apenas a 1ª execução incrementa os itens ordenados por SKU.
    @Transactional
    public String releaseLegacy(UUID orderId, List<InventoryItemDTO> items) {
        Map<String, Integer> normalizedItems = normalizeItems(items);
        Instant now = Instant.now();

        int inserted = reservationRepository.insertIfAbsent(
                orderId, ReservationStatus.RELEASED.name(), now);
        if (inserted == 1) {
            for (Map.Entry<String, Integer> entry : normalizedItems.entrySet()) {
                inventoryRepository.increment(entry.getKey(), entry.getValue());
            }
            log.info("Estoque devolvido para pedido LEGACY cancelado: orderId={}, skus={}",
                    orderId, normalizedItems.size());
            return "released";
        }

        log.info("Cancelamento LEGACY duplicado ignorado (noop, lápide já existe): orderId={}", orderId);
        return "noop";
    }

    @Transactional(readOnly = true)
    public ReservationResponseDTO getReservation(UUID reservationId) {
        StockReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        List<InventoryItemDTO> items = reservationItemRepository
                .findByIdReservationIdOrderByIdSkuCodeAsc(reservationId)
                .stream()
                .map(i -> new InventoryItemDTO(i.getId().getSkuCode(), i.getQuantity()))
                .toList();
        return new ReservationResponseDTO(
                reservation.getId(),
                reservation.getStatus(),
                items,
                reservation.getCreatedAt(),
                reservation.getUpdatedAt()
        );
    }

    private Map<String, Integer> normalizeItems(List<InventoryItemDTO> items) {
        Map<String, Integer> normalized = new TreeMap<>();
        for (InventoryItemDTO item : items) {
            normalized.merge(item.skuCode(), item.quantity(), Math::addExact);
        }
        return normalized;
    }

    private void recordReservationMetric(String result) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_RESERVATIONS, "result", result).increment();
        }
    }

    private void recordReleaseMetric(String result) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_RELEASES, "result", result).increment();
        }
    }
}
