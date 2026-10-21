-- Status já é VARCHAR(20) NOT NULL desde a V2 (aceita PENDING e EXPIRED sem alterar o tipo).

-- Modo de reserva: pedidos anteriores ao livro-razão ficam marcados como LEGACY pelo DEFAULT;
-- a aplicação grava LEDGER nos novos pedidos criados a partir desta versão.
ALTER TABLE tb_orders
    ADD COLUMN reservation_mode VARCHAR(10) NOT NULL DEFAULT 'LEGACY';

-- Data/hora de confirmação: permanece NULL enquanto o pedido está PENDING/EXPIRED ou se falhou
-- antes de confirmar. Pedidos legados já confirmados/cancelados/concluídos recebem order_time.
ALTER TABLE tb_orders
    ADD COLUMN confirmed_at TIMESTAMPTZ NULL;

UPDATE tb_orders
SET confirmed_at = order_time
WHERE status IN ('CONFIRMED', 'CANCELLED', 'COMPLETED')
  AND confirmed_at IS NULL;

-- Índices parciais enxutos para o reconciliador buscar apenas pedidos pendentes/expirados vencidos.
CREATE INDEX idx_orders_pending_order_time
    ON tb_orders (order_time)
    WHERE status = 'PENDING';

CREATE INDEX idx_orders_expired_order_time
    ON tb_orders (order_time)
    WHERE status = 'EXPIRED';

-- Outbox: backoff exponencial baseado em next_attempt_at em vez de varredura fixa imediata.
ALTER TABLE tb_outbox_event
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_outbox_event_status_next_attempt_at
    ON tb_outbox_event (status, next_attempt_at, created_at);
