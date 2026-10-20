-- tb_orders.id: BIGSERIAL -> UUID (v7), preservando os pedidos existentes e suas referências
-- (tb_order_items.order_id e tb_outbox_event.aggregate_id). Os novos IDs são gerados pela aplicação.

-- 1. Novo ID dos pedidos existentes: UUIDv7 montado a partir do order_time, para os pedidos
--    antigos ficarem na mesma ordem temporal dos novos. Os 6 primeiros bytes recebem o timestamp
--    em ms; os bits 52/53 transformam a versão 4 do gen_random_uuid() em 7 (0100 -> 0111).
--    localtimestamp (e não now()) mantém o tipo TIMESTAMP sem fuso: com now() o coalesce vira
--    TIMESTAMPTZ e o resultado passaria a depender do TimeZone da sessão que roda a migration.
ALTER TABLE tb_orders ADD COLUMN new_id UUID;

UPDATE tb_orders
SET new_id = encode(
        set_bit(set_bit(
            overlay(uuid_send(gen_random_uuid())
                    placing substring(int8send((extract(epoch FROM coalesce(order_time, localtimestamp)) * 1000)::bigint) FROM 3)
                    FROM 1 FOR 6),
        52, 1), 53, 1),
    'hex')::uuid;

-- 2. Propaga o novo ID para quem referencia o pedido.
ALTER TABLE tb_order_items ADD COLUMN new_order_id UUID;

UPDATE tb_order_items i
SET new_order_id = o.new_id
FROM tb_orders o
WHERE i.order_id = o.id;

ALTER TABLE tb_outbox_event ADD COLUMN new_aggregate_id UUID;

UPDATE tb_outbox_event e
SET new_aggregate_id = o.new_id
FROM tb_orders o
WHERE e.aggregate_id = o.id;

-- 3. Remove as colunas antigas. Derrubar tb_orders.id também derruba a PK, a sequence do
--    BIGSERIAL e os índices da V4 que incluem id; eles são recriados no passo 5.
ALTER TABLE tb_order_items DROP CONSTRAINT tb_order_items_order_id_fkey;
ALTER TABLE tb_order_items DROP COLUMN order_id;
ALTER TABLE tb_order_items RENAME COLUMN new_order_id TO order_id;

ALTER TABLE tb_outbox_event DROP COLUMN aggregate_id;
ALTER TABLE tb_outbox_event RENAME COLUMN new_aggregate_id TO aggregate_id;
-- Falha a migration (em vez de apagar dados) se houver evento apontando para pedido inexistente.
ALTER TABLE tb_outbox_event ALTER COLUMN aggregate_id SET NOT NULL;

ALTER TABLE tb_orders DROP COLUMN id;
ALTER TABLE tb_orders RENAME COLUMN new_id TO id;
ALTER TABLE tb_orders ALTER COLUMN id SET NOT NULL;

-- 4. Chaves.
ALTER TABLE tb_orders ADD CONSTRAINT tb_orders_pkey PRIMARY KEY (id);
ALTER TABLE tb_order_items
    ADD CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES tb_orders (id);

-- 5. Índices (os mesmos da V4, agora sobre as colunas UUID).
CREATE INDEX idx_orders_userid_order_time ON tb_orders (userid, order_time DESC, id DESC);
CREATE INDEX idx_orders_order_time ON tb_orders (order_time DESC, id DESC);
CREATE INDEX idx_order_items_order_id ON tb_order_items (order_id);
