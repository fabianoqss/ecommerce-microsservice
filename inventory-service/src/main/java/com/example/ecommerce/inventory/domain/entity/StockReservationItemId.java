package com.example.ecommerce.inventory.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class StockReservationItemId implements Serializable {

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(name = "sku_code", length = 255, nullable = false)
    private String skuCode;

    public StockReservationItemId() {
    }

    public StockReservationItemId(UUID reservationId, String skuCode) {
        this.reservationId = reservationId;
        this.skuCode = skuCode;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public void setReservationId(UUID reservationId) {
        this.reservationId = reservationId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockReservationItemId that)) return false;
        return Objects.equals(reservationId, that.reservationId)
                && Objects.equals(skuCode, that.skuCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reservationId, skuCode);
    }
}
