ALTER TABLE tb_orders
    ALTER COLUMN status TYPE VARCHAR(32),
    ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_reconciliation_at TIMESTAMPTZ;

CREATE INDEX idx_orders_reconciliation_due
    ON tb_orders (next_reconciliation_at ASC NULLS FIRST, order_time)
    WHERE status IN ('PENDING', 'EXPIRED');
