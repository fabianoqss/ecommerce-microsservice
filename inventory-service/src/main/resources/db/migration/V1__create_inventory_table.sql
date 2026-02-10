CREATE TABLE tb_inventory (
     id BIGSERIAL PRIMARY KEY,
     sku_code VARCHAR(255) NOT NULL,
     quantity INT NOT NULL
);

CREATE UNIQUE INDEX uk_tb_inventory_sku_code
    ON tb_inventory (sku_code);