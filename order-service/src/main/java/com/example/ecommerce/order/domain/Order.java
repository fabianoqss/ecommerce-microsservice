package com.example.ecommerce.order.domain;

import com.example.ecommerce.order.exception.InvalidOrderStatusTransitionException;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tb_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order {


    // UUIDv7 (RFC 9562): gerado na aplicação, não enumerável e ordenado por tempo, então os
    // inserts vão para o fim do índice B-tree como num BIGSERIAL (o v4 aleatório fragmenta o índice).
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    // CHAR(64) na V8: SHA-256 em hex tem tamanho fixo; sem o JdbcTypeCode o validate espera VARCHAR.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    private LocalDateTime orderTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    // Novos pedidos usam o livro-razão (LEDGER) no inventory; pedidos anteriores à V7 são LEGACY.
    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_mode", nullable = false, length = 10)
    private ReservationMode reservationMode = ReservationMode.LEDGER;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    private String userID;

    private BigDecimal totalValue;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items;

    // Optimistic locking: duas transições simultâneas no mesmo pedido (ex.: dois cancelamentos,
    // ou confirmar após o reconciliador expirar) não passam as duas.
    @Version
    @Column(nullable = false)
    private Long version;

    // Auditoria do cancelamento: quem (sub do token), quando e por quê.
    private LocalDateTime cancelledAt;

    private String cancelledBy;

    @Column(length = 255)
    private String cancellationReason;

    /**
     * PENDING -> CONFIRMED. Pedido já expirado pelo reconciliador nunca pode ser confirmado tarde.
     */
    public void confirm(Instant now) {
        Objects.requireNonNull(now, "confirmedAt não pode ser nulo");
        if (status != OrderStatus.PENDING) {
            throw new InvalidOrderStatusTransitionException(id, status, OrderStatus.CONFIRMED);
        }
        status = OrderStatus.CONFIRMED;
        confirmedAt = now;
    }

    public void confirm() {
        confirm(Instant.now());
    }

    /**
     * PENDING -> EXPIRED. Gravado antes do DELETE da reserva no inventory para impedir que uma
     * confirmação atrasada do fluxo síncrono vença a corrida.
     */
    public void expire() {
        if (status != OrderStatus.PENDING) {
            throw new InvalidOrderStatusTransitionException(id, status, OrderStatus.EXPIRED);
        }
        status = OrderStatus.EXPIRED;
    }

    /**
     * PENDING | EXPIRED | CONFIRMED -> CANCELLED. Retorna false se o pedido já estava cancelado
     * (operação idempotente: repetir o cancelamento não reprocessa estoque nem publica outro evento).
     */
    public boolean cancel(String actorSubject, String reason, LocalDateTime now) {
        if (status == OrderStatus.CANCELLED) {
            return false;
        }
        if (status != OrderStatus.CONFIRMED && status != OrderStatus.PENDING && status != OrderStatus.EXPIRED) {
            throw new InvalidOrderStatusTransitionException(id, status, OrderStatus.CANCELLED);
        }
        status = OrderStatus.CANCELLED;
        cancelledAt = now;
        cancelledBy = actorSubject;
        cancellationReason = reason;
        return true;
    }
}
