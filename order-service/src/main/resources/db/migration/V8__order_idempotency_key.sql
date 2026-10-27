ALTER TABLE tb_orders ADD COLUMN idempotency_key UUID NULL;
ALTER TABLE tb_orders ADD COLUMN request_hash CHAR(64) NULL;

CREATE UNIQUE INDEX ux_orders_idempotency_key
ON tb_orders (userid, idempotency_key)
WHERE idempotency_key IS NOT NULL;
