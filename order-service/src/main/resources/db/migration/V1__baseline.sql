CREATE TABLE tb_orders (
    id BIGSERIAL PRIMARY KEY,
    order_time TIMESTAMP,
    status INTEGER,
    userid VARCHAR(255),
    total_value NUMERIC(19,2)
);

CREATE TABLE tb_order_items (
    id BIGSERIAL PRIMARY KEY,
    product_id VARCHAR(255),
    quantity INTEGER,
    price NUMERIC(19,2),
    order_id BIGINT REFERENCES tb_orders(id)
);
