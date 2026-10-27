package com.example.ecommerce.order.domain.entity;

import com.example.ecommerce.order.domain.enums.OrderStatus;
import com.example.ecommerce.order.domain.enums.ReservationMode;
import com.example.ecommerce.order.exception.InvalidOrderStatusTransitionException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    private LocalDateTime orderTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_mode", nullable = false, length = 10)
    private ReservationMode reservationMode = ReservationMode.LEDGER;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts;

    @Column(name = "next_reconciliation_at")
    private Instant nextReconciliationAt;

    private String userID;

    private BigDecimal totalValue;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items;

    @Version
    @Column(nullable = false)
    private Long version;

    private LocalDateTime cancelledAt;

    private String cancelledBy;

    @Column(length = 255)
    private String cancellationReason;

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

    public void expire() {
        if (status != OrderStatus.PENDING) {
            throw new InvalidOrderStatusTransitionException(id, status, OrderStatus.EXPIRED);
        }
        status = OrderStatus.EXPIRED;
    }

    public void recordReconciliationFailure(Instant nextAttemptAt, int maxAttempts) {
        if (status != OrderStatus.EXPIRED) {
            throw new InvalidOrderStatusTransitionException(id, status, OrderStatus.RECONCILIATION_FAILED);
        }
        reconciliationAttempts++;
        if (reconciliationAttempts >= maxAttempts) {
            status = OrderStatus.RECONCILIATION_FAILED;
            nextReconciliationAt = null;
        } else {
            nextReconciliationAt = Objects.requireNonNull(nextAttemptAt, "Próxima reconciliação não pode ser nula");
        }
    }

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
