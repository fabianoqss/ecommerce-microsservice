-- Listagem paginada do cliente: WHERE userid = ? ORDER BY order_time DESC, id DESC.
-- O índice composto atende o filtro e a ordenação sem sort em memória.
CREATE INDEX idx_orders_userid_order_time ON tb_orders (userid, order_time DESC, id DESC);

-- Listagem do admin: ORDER BY order_time DESC, id DESC sem filtro.
CREATE INDEX idx_orders_order_time ON tb_orders (order_time DESC, id DESC);

-- Carga dos itens de uma página de pedidos (WHERE order_id IN (...)). O Postgres não indexa FK sozinho.
CREATE INDEX idx_order_items_order_id ON tb_order_items (order_id);
