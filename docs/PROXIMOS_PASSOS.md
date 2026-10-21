# Próximos passos (revisão de 2026-10-20)

Estado: branch `feat/keycloak-bff`. Os lotes B–F da revisão foram concluídos em 2026-10-20, com
`./mvnw verify` verde na raiz e teste ponta a ponta no ambiente Docker (login Keycloak → pedido →
cancelamento por evento → estoque devolvido; 409 sem estoque; livro de reservas bloqueado na borda).

## Concluído
- **Lote B — segurança e código morto:** `product-service/infra` removido; token de serviço só no
  `InventoryClient`; cookie `Secure` por padrão; paginação com teto e limite de itens; tracing/SQL por
  ambiente; actuator do gateway na porta interna 8090; idempotência do notification no Redis; rotas e
  dependências mortas removidas; configs quebradas corrigidas.
- **Lote C — infraestrutura:** RabbitMQ 4.2.5 com vhost `ecommerce` e um usuário por serviço
  (`rabbitmq-init`); Eureka com HTTP Basic; Keycloak 26.7.5; imagens com versão fixa;
  `scripts/generate-env.sh`.
- **Lote D — documentação:** README, `PROJECT_STATUS.md`, `PLANO_EXECUCAO.md` e Bruno sem auth-service;
  `.env.example` duplicados removidos.
- **Lote E — estrutura:** POM agregador (`ecommerce-parent`), módulo `common-security`, wrapper único,
  Dockerfiles não-root com contexto na raiz e `.dockerignore` allowlist; pacotes
  `com.example.ecommerce.<serviço>`; ProblemDetail; order-service único dono da topologia RabbitMQ.
- **Lote F — reservas idempotentes** (`docs/specs/reservas-idempotentes.md`, Revisão v2): livro-razão no
  inventory; criação com PENDING + reconciliador; cancelamento por evento v3 com transição LEGACY;
  `/reserve` e `/release` removidos.

## Permissões RabbitMQ resultantes
| Usuário | configure | write | read |
|---|---|---|---|
| order-service (produtor, dono da topologia) | `^(order\|inventory)\.` | idem | idem |
| notification-service | nada | nada | `order.created.queue`, `order.cancelled.queue` |
| inventory-service | nada | nada | `inventory.order.cancelled.queue` |
| admin (console) | sem acesso AMQP | | |

## Pendências e sugestões
- Alertas no Prometheus: profundidade > 0 nas DLQs, `orders.pending` crescendo, pedido PENDING mais
  velho que X minutos, outbox em FAILED.
- Expurgo periódico do livro-razão (`tb_stock_reservation` RELEASED antigas).
- Frontend: `GET /api/product` agora é paginado (`content`, `page`, `size`, `totalElements`, `totalPages`).
- IDE: reimportar o projeto pelo `pom.xml` da raiz (módulos e pacotes novos).
- `.env` local: `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` antigos não são mais usados e podem ser apagados.
