-- Estoque de demonstração, SÓ EM DEV (montado apenas pelo docker-compose.dev.yml).
-- O sku_code é o _id do produto em products.js. Idempotente e não destrutivo:
-- um SKU que já existe mantém a quantidade atual (reservas e ajustes feitos pela API).
-- Inclui um item esgotado e um com estoque baixo para exercitar esses fluxos.

INSERT INTO tb_inventory (sku_code, quantity) VALUES
    ('670000000000000000000001', 50),
    ('670000000000000000000002', 40),
    ('670000000000000000000003', 35),
    ('670000000000000000000004', 12),
    ('670000000000000000000005', 25),
    ('670000000000000000000006', 8),
    ('670000000000000000000007', 60),
    ('670000000000000000000008', 30),
    ('670000000000000000000009', 20),
    ('670000000000000000000010', 45),
    ('670000000000000000000011', 0),
    ('670000000000000000000012', 3),
    ('670000000000000000000013', 28),
    ('670000000000000000000014', 10),
    ('670000000000000000000015', 5),
    ('670000000000000000000016', 18),
    ('670000000000000000000017', 70),
    ('670000000000000000000018', 55),
    ('670000000000000000000019', 100),
    ('670000000000000000000020', 100)
ON CONFLICT (sku_code) DO NOTHING;
