package com.example.ecommerce.order.domain;

import com.example.ecommerce.order.exception.InvalidOrderStatusTransitionException;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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

    private LocalDateTime orderTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    private String userID;

    private BigDecimal totalValue;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items;

    // Optimistic locking: duas transições simultâneas no mesmo pedido (ex.: dois cancelamentos,
    // ou cancelar enquanto o admin conclui) não passam as duas; a segunda recebe 409.
    @Version
    @Column(nullable = false)
    private Long version;

    // Auditoria do cancelamento: quem (sub do token), quando e por quê.
    private LocalDateTime cancelledAt;

    private String cancelledBy;

    @Column(length = 255)
    private String cancellationReason;

    /**
     * CONFIRMED -> CANCELLED. Retorna false se o pedido já estava cancelado (operação idempotente:
     * repetir o cancelamento não reprocessa estoque nem publica outro evento).
     */
    public boolean cancel(String actorSubject, String reason, LocalDateTime now) {
        if (status == OrderStatus.CANCELLED) {
            return false;
        }
        if (status != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStatusTransitionException(id, status, OrderStatus.CANCELLED);
        }
        status = OrderStatus.CANCELLED;
        cancelledAt = now;
        cancelledBy = actorSubject;
        cancellationReason = reason;
        return true;
    }
}
