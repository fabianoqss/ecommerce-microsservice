package com.example.ecommerce.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

// Item normalizado associado a uma reserva de estoque.
@Entity
@Table(name = "tb_stock_reservation_item")
public class StockReservationItem {

    @EmbeddedId
    private StockReservationItemId id;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    public StockReservationItem() {
    }

    public StockReservationItem(StockReservationItemId id, Integer quantity) {
        this.id = id;
        this.quantity = quantity;
    }

    public StockReservationItemId getId() {
        return id;
    }

    public void setId(StockReservationItemId id) {
        this.id = id;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
