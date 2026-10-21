-- Livro-razão de reservas idempotentes de estoque por reservationId (orderId).
CREATE TABLE tb_stock_reservation (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE tb_stock_reservation_item (
    reservation_id UUID NOT NULL REFERENCES tb_stock_reservation(id) ON DELETE CASCADE,
    sku_code VARCHAR(255) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (reservation_id, sku_code)
);
