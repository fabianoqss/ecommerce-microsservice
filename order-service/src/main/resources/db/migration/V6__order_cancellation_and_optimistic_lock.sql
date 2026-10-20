-- Optimistic locking (@Version) e auditoria do cancelamento de pedidos.
ALTER TABLE tb_orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE tb_orders ADD COLUMN cancelled_at TIMESTAMP;
ALTER TABLE tb_orders ADD COLUMN cancelled_by VARCHAR(255);
ALTER TABLE tb_orders ADD COLUMN cancellation_reason VARCHAR(255);
