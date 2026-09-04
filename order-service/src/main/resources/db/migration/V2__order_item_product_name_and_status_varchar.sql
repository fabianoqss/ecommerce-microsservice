-- Ambiente de dev/TCC, sem dados reais a preservar.
TRUNCATE TABLE tb_order_items, tb_orders RESTART IDENTITY CASCADE;

ALTER TABLE tb_orders ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
ALTER TABLE tb_orders ALTER COLUMN status SET NOT NULL;

ALTER TABLE tb_order_items ADD COLUMN product_name VARCHAR(255);
