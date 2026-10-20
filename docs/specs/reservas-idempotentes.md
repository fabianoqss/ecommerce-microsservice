# Reservas de estoque idempotentes por orderId

## Problema
inventory-service expõe POST /api/inventory/reserve e /release recebendo só [{skuCode, quantity}].
release = `quantity + qty` incondicional. Sem identificador da operação:
- timeout ambíguo no reserve: estoque baixado sem pedido, sem como saber/reconciliar;
- timeout ambíguo no release / retry do cliente / corrida T1-rollback-T2: estoque devolvido 2x;
- compensação que falha só vira log (sem retentativa durável).

## Ideia central
Toda reserva passa a ter identidade: reservationId = orderId (UUIDv7 gerado no order-service ANTES
de reservar). O inventory guarda um livro-razão de reservas com máquina de estados; reservar e liberar
viram operações idempotentes. Com idempotência, retentativa passa a ser segura, e a consistência vem
de "repetir até dar certo" + reconciliação, não de rollback distribuído.

## inventory-service
Tabelas (Flyway):
- tb_stock_reservation(id UUID PK, status VARCHAR(20) NOT NULL, created_at, updated_at)
  status: RESERVED | RELEASED
- tb_stock_reservation_item(reservation_id UUID FK, sku_code, quantity, PK(reservation_id, sku_code))

API (só SCOPE_inventory:reserve; gateway continua com denyAll):
- PUT /api/inventory/reservations/{reservationId}  body {items}
  - não existe: numa transação, INSERT da reserva (PK protege concorrência: INSERT ... ON CONFLICT DO NOTHING;
    0 linhas => outro request criou, cai no caso "existe") + decrementos condicionais ordenados por SKU.
    Algum SKU sem estoque => rollback total => 409.  Sucesso => 201.
  - existe RESERVED com os mesmos itens => 200 (repetição idempotente, sem efeito).
  - existe RESERVED com itens diferentes => 422 (reuso da chave com outro payload).
  - existe RELEASED => 409 (reserva já liberada/cancelada não ressuscita).
- DELETE /api/inventory/reservations/{reservationId}
  - UPDATE tb_stock_reservation SET status='RELEASED' WHERE id=? AND status='RESERVED'.
    1 linha => incrementa os itens (mesma transação, ordenado por SKU) => 204.
    0 linhas e existe RELEASED => 204 (idempotente).
    não existe => INSERT de uma "lápide" RELEASED (sem itens) => 204. Protege a corrida
    "release chega antes de um reserve atrasado": o reserve atrasado encontra RELEASED e recebe 409.
- GET /api/inventory/reservations/{reservationId} => estado (para reconciliação/diagnóstico).
O UPDATE condicional no status é o que garante que só UMA liberação incrementa o estoque, mesmo com
requests concorrentes ou repetidos.

## order-service
Id gerado antes: Order deixa de usar @UuidGenerator; o service gera o UUIDv7 (Hibernate UuidVersion7Strategy
ou java-uuid-generator) e atribui. Com @Version presente, o Spring Data continua detectando "novo" pela version nula.

Criação (orquestração síncrona, durável):
1. Tx curta: persiste Order PENDING (com itens e total). Commit. O pedido existe antes do efeito remoto.
2. PUT reserva(orderId) fora de transação, com até 2 retentativas rápidas (seguro: idempotente).
3a. Sucesso => tx: PENDING -> CONFIRMED + outbox ORDER_CREATED => 201.
3b. 409 sem estoque => tx: PENDING -> CANCELLED (motivo "estoque insuficiente") => 409.
3c. Falha ambígua (timeout/5xx/queda) => pedido fica PENDING => 503 para o cliente.
4. Reconciliador (@Scheduled): pedidos PENDING há mais de N segundos => DELETE reserva(orderId)
   (idempotente; se o reserve nunca aplicou, vira lápide) => PENDING -> CANCELLED.
   Política: pedido PENDING vencido NUNCA é confirmado tarde (o cliente já recebeu erro).

Cancelamento (coreografia, assíncrono):
1. Tx: CONFIRMED -> CANCELLED + auditoria + outbox ORDER_CANCELLED. Sem chamada HTTP na transação.
2. inventory-service consome order.cancelled (RabbitMQ) e chama a liberação idempotente por orderId.
   Entrega at-least-once + liberação idempotente = efeito exatamente uma vez.
   O estoque volta segundos depois (consistência eventual) — aceitável para devolução.

## Transição (pedidos já existentes)
Pedidos criados antes do livro-razão não têm reserva registrada: liberar por orderId viraria lápide e o
estoque nunca voltaria. Coluna nova em tb_orders: reservation_mode LEGACY|LEDGER (V7: existentes = LEGACY).
O consumidor de order.cancelled precisa saber o modo: o evento v3 carrega items + reservationMode;
LEGACY => incremento pelos itens do evento, deduplicado pelo eventId (tabela de eventos processados no inventory).
Endpoints antigos /reserve e /release ficam deprecated até não haver mais pedidos LEGACY CONFIRMED.

## Observabilidade
- inventory.reservations{result=created|replayed|insufficient|conflict}
- inventory.releases{result=released|noop|tombstone}
- orders.pending (gauge) e orders.reconciled{outcome}; alerta para PENDING mais velho que X minutos.
- logs com orderId/reservationId; tracing já propaga.

## Testes
- inventory: reserva repetida não baixa 2x; itens divergentes => 422; release 2x incrementa 1x;
  release antes do reserve => lápide e reserve posterior 409; concorrência (2 threads) no release.
- order: timeout no reserve => PENDING => reconciliador cancela e libera; 409 => CANCELLED;
  cancelamento publica evento e inventory libera uma vez (Testcontainers com RabbitMQ).

---
## Revisão v2 (após crítica do gemini-4-argon-high, pontos verificados)
1. DELETE (liberação) em duas etapas, nesta ordem:
   a) INSERT lápide (id, 'RELEASED') ON CONFLICT (id) DO NOTHING — espera um INSERT concorrente em voo do PUT.
      1 linha => lápide (204, tombstone).
   b) senão: UPDATE ... SET status='RELEASED' WHERE id=? AND status='RESERVED' (novo snapshot vê o commit do PUT).
      1 linha => incrementa itens (released); 0 => já liberada (noop).
2. Reconciliador: SELECT ... WHERE status='PENDING' AND order_time < cutoff FOR UPDATE SKIP LOCKED;
   transição PENDING -> EXPIRED (com @Version) e commit ANTES do DELETE da reserva; depois EXPIRED -> CANCELLED.
   createOrder ao confirmar falha no @Version => responde 503. N >> tempo máximo do fluxo síncrono.
   Índice parcial: (order_time) WHERE status='PENDING'.
3. RabbitMQ: fila própria inventory.order.cancelled.queue + DLQ, declarada também no order-service (mandatory=true
   não garante entrega à fila do inventory se outra fila já estiver ligada à routing key).
   Listener com retry/backoff e default-requeue-rejected=false.
4. Outbox: backoff exponencial (next_attempt_at) em vez de FAILED após 10 x 5s; métrica/alerta de FAILED e DLQ.
5. Segurança: gateway denyAll em /api/inventory/reservations/**; inventory com matcher SCOPE_inventory:reserve
   ANTES dos matchers genéricos de GET/PUT.
6. Consumidor ignora (ACK) eventos order.cancelled com version < 3.
7. Itens normalizados (Map sku -> soma, ordenado) na reserva e na comparação de replay (já agregado no order-service).
8. 409 só para estoque insuficiente; reserva já liberada => 410 Gone. Retentativas só em timeout/5xx.
9. PENDING: Order.cancel/expire aceitam PENDING; tentativas que falham por falta de estoque não aparecem em
   "Meus pedidos" (filtrar ou não persistir histórico de checkout falho — decisão de produto).
Simplificações aceitas:
- Manter @UuidGenerator (o PENDING é persistido antes do PUT, o id já existe).
- LEGACY sem tabela de eventos processados: consumidor faz INSERT lápide ON CONFLICT DO NOTHING; 1 linha => incrementa
  pelos itens do evento. Idempotência por orderId.
- Remover /reserve e /release logo após o rolling deploy.
