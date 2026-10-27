package com.example.ecommerce.order.domain;

import com.example.ecommerce.order.exception.InvalidOrderStatusTransitionException;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

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

    private LocalDateTime orderTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

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
