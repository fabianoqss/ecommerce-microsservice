# Plano de execução — Fechamento do P0 (segurança, identidade, estoque, consistência)

> Este documento é a cópia, para dentro do repositório, do plano produzido e executado com apoio do Claude Code em 2026-09-04. O plano original vive fora do repo (arquivo de sessão do Claude Code) — esta cópia existe para que o progresso fique rastreável junto do código. Ver também [`PROJECT_STATUS.md`](../PROJECT_STATUS.md) para o resumo executivo atualizado.

## Status por fase (atualizado em 2026-09-04)

| Fase | Descrição | Status |
|---|---|---|
| 1 | auth-service: Flyway, credenciais dinâmicas, limpeza de CORS | ✅ Concluída e commitada (`e9122e3`) |
| 2 | order-service: identidade via JWT + autorização por dono | ✅ Concluída (não commitada ainda) |
| 3 | order-service: adotar Flyway | ✅ Concluída (não commitada ainda) |
| 4 | Estoque atômico + snapshot de nome + status como texto | ✅ Concluída (não commitada ainda) |
| 5 | Validação e erros padronizados (`ProblemDetail`) | ✅ Concluída (não commitada ainda) |
| 6 | Outbox, retry, DLQ, idempotência | ✅ Concluída e validada |
| 7 | Fechar rede (portas internas) + remover defaults inseguros | ✅ Concluída e validada |

Fases 2–5 foram validadas manualmente ponta a ponta pelo gateway (`docker compose up --build`, registro/login, criação de produto/estoque como admin, criação de pedido como usuário, checagem de ownership, estoque insuficiente, payload inválido) — ver seção "Notas de implementação" abaixo para os dois problemas reais encontrados e corrigidos durante essa validação.

---

## Contexto

O projeto (TCC de microsserviços) tem uma base funcional, mas `PROJECT_STATUS.md` (auditoria de 2026-08-19) e `PLANO_DE_RETOMADA_ECOMMERCE.md` (Codex, 2026-09-03, na Área de Trabalho) documentam os mesmos 22 problemas de P0 como bloqueadores para considerar o MVP concluído. Verifiquei cada um diretamente no código nesta sessão — todos continuavam presentes (`OrderService.setUserID(request.userId())`, `ddl-auto: update` em auth/order, admin fixo em `DataInitializer`, todas as portas internas publicadas no host).

O usuário quer finalizar o projeto definitivamente (não vai mexer mais depois) e **não terá frontend**. Este plano cobre a Seção 2 ("P0") dos dois documentos: identidade/autorização do pedido, estoque atômico, consistência de eventos (Outbox), validação/erros padronizados e remoção de segredos fixos. Testes automatizados extensos ficam para uma rodada seguinte (fora deste plano).

### Decisões validadas com o usuário
1. **Identidade do pedido** = e-mail (`sub` do JWT). Sem mudar `TokenService.java` do auth-service.
2. **Defesa em profundidade proporcional ao risco**: fechar as portas dos serviços internos no `docker-compose.yml` (só `api-gateway` fica publicado) **e** o `order-service` valida o JWT localmente (não confia em header repassado), porque é o serviço mais sensível (decide posse do pedido). Product/inventory ficam só atrás do isolamento de rede por ora — validação local neles é follow-up opcional, fora de escopo.
3. **Consistência pedido+estoque+evento**: Outbox real (tabela `tb_outbox_event` + poller `@Scheduled`) e baixa de estoque atômica via `UPDATE ... WHERE quantity >= :qty`, com compensação (release) se o save do pedido falhar depois da baixa já confirmada no inventory-service.

### Confirmado por leitura direta do código na sessão
- `Order.java:29` — `private OrderStatus status;` sem `@Enumerated`, persistido como ORDINAL (corrigido na Fase 4).
- `OrderService.java` — `orderRepository.save(order)` rodava antes do `rabbitTemplate.convertAndSend(...)`, sem outbox, sem confirm callback; `toResponseDTO` hardcodava `productName = null`; exceções eram `RuntimeException` genéricas → 500 sem corpo padronizado.
- `order-service/pom.xml` — sem `spring-boot-starter-security`, sem `com.auth0:java-jwt`, sem Flyway, sem `spring-dotenv`.
- `docker-compose.yml` — todos os serviços de negócio e o `eureka-server` publicam `ports:` no host; healthchecks usam `curl http://localhost:PORT` **dentro do próprio container**, então remover `ports:` não quebra healthcheck nem comunicação inter-serviço.
- `inventory-service` já usava Flyway corretamente — modelo de referência para auth-service e order-service.
- `auth-service/infra/security/SecurityFilter.java` + `TokenService.java` já validavam JWT localmente — modelo de referência para o novo filtro do order-service.

---

## Ordem de execução (7 fases, cada uma buildável e testável isoladamente)

```
Fase 1 (auth: Flyway + credenciais)  →  Fase 2 (order: identidade/JWT)  →  Fase 3 (order: Flyway)
   →  Fase 4 (estoque atômico + outbox de dados)  →  Fase 5 (erros padronizados)
   →  Fase 6 (Outbox pattern + DLQ + idempotência)  →  Fase 7 (fechar portas + remover defaults)
```

A Fase 3 (Flyway no order-service) foi isolada porque é pré-requisito tanto da Fase 4 (novas colunas `product_name`, `status` como texto) quanto da Fase 6 (tabela `tb_outbox_event`). A Fase 7 (rede) vai por último de propósito: mantém as portas abertas durante todo o desenvolvimento/teste manual das fases anteriores, facilitando `curl localhost:808x` para depuração.

---

## FASE 1 — auth-service: Flyway, credenciais, limpeza de CORS ✅

**1.1 Flyway no auth-service** (mesmo padrão do `inventory-service`)
- `auth-service/pom.xml`: `spring-boot-starter-flyway` + `flyway-database-postgresql`.
- `auth-service/application.yml`: `ddl-auto: update` → `validate`; bloco `flyway` (enabled, locations `classpath:db/migration`, `baseline-on-migrate: true`, `baseline-version: 1`).
- `auth-service/src/main/resources/db/migration/V1__create_users_table.sql`: schema atual de `User.java` + `CREATE UNIQUE INDEX uk_users_email ON users (email);`.
- `User.java`: `@Column(nullable = false, unique = true)` em `email`.
- Ação operacional: resetar o volume/tabela `users` do banco `auth_service` de dev antes do primeiro boot com Flyway.

**1.2 Remover admin fixo**
- `DataInitializer.java`: lê `${app.admin.email:}` / `${app.admin.password:}`; se algum estiver vazio, não cria admin (loga aviso); se ambos presentes, cria (ou detecta que já existe).
- `docker-compose.yml`, serviço `auth-service`: `ADMIN_EMAIL`/`ADMIN_PASSWORD` no `environment:`.

**1.3 Remover CORS morto do gateway**
- `api-gateway/infra/config/WebMvcConfig.java` deletado (não há mais frontend).

**1.4 `.env.example`** na raiz, documentando `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`.

---

## FASE 2 — order-service: identidade via JWT + autorização por dono ✅

**2.1 Dependências**: `spring-boot-starter-security`, `com.auth0:java-jwt:4.4.0`, `me.paulschwarz:spring-dotenv:4.0.0`, `spring-boot-starter-validation`.

**2.2 Segurança local** — pacote `order-service/.../infra/security/`, inspirado em `auth-service`:
- `TokenService.java`: valida HMAC256 + issuer `auth-service`, sem lookup em banco.
- `OrderSecurityFilter.java`: extrai `Bearer`, popula `SecurityContextHolder` com e-mail + role do token.
- `SecurityConfig.java`: stateless, `permitAll` em `/actuator/**` e `/error` (crítico para o healthcheck do Docker e para não mascarar erros — ver nota de implementação), resto autenticado.

**2.3 Config**: `jwt.secret: ${JWT_SECRET}`; `JWT_SECRET` adicionado ao `docker-compose.yml` do order-service.

**2.4 Remover `userId` do body**: `OrderRequestDTO`/`OrderItemRequestDTO` com Bean Validation (`@NotEmpty`, `@NotBlank`, `@Min(1)`), sem `userId`.

**2.5 Controller/Service**: `OrderController` recebe `Authentication`; `OrderService.createOrder` usa o e-mail do token; `findAll` retorna tudo só para `ROLE_ADMIN`; `findById` lança `OrderAccessDeniedException` se não for dono nem admin; `OrderRepository.findByUserID` novo.

---

## FASE 3 — order-service: adotar Flyway ✅

- `order-service/pom.xml`: Flyway (mesmas deps da Fase 1).
- `application.yml`: `ddl-auto: validate` + bloco `flyway`.
- `V1__baseline.sql`: schema atual (`tb_orders`, `tb_order_items`).

---

## FASE 4 — Estoque atômico + snapshot de nome + status como texto ✅

**4.1 inventory-service**
- `InventoryRepository`: `decrement`/`increment` via `@Modifying @Query` (UPDATE condicional `quantity >= :qty`).
- `InventoryService.reserve()`/`release()` (`@Transactional`) — `InsufficientStockException` se `updated == 0`; rollback automático desfaz decrementos parciais do mesmo lote.
- Endpoints `POST /api/inventory/reserve` e `POST /api/inventory/release`.
- Exceções `InsufficientStockException`, `InventoryNotFoundException`.

**4.2 order-service**
- `OrderService.createOrder`: busca produtos → `reserve()` no inventory-service → persiste pedido → em caso de falha após o reserve, chama `release()` como compensação (o rollback local não desfaz a reserva remota já commitada).
- `OrderItem.productName` preenchido de verdade.
- `Order.status` com `@Enumerated(EnumType.STRING)`; `OrderStatus` = `PENDING, CONFIRMED, CANCELLED, COMPLETED`; pedido nasce `CONFIRMED` (sem payment-service).
- `V2__order_item_product_name_and_status_varchar.sql`.

---

## FASE 5 — Validação e erros padronizados ✅

- `GlobalExceptionHandler` (`@RestControllerAdvice` + `ProblemDetail`, RFC 7807) em order-service e inventory-service:
  - `OrderNotFoundException`/`InventoryNotFoundException` → 404
  - `ProductNotFoundException` → 404
  - `OrderAccessDeniedException` → 403
  - `InsufficientStockException` → 409
  - `MethodArgumentNotValidException` → 400 com lista de campos
  - `FeignException` (order-service) → 404/502 conforme status remoto
  - fallback `Exception` → 500 sem vazar stacktrace

---

## FASE 6 — Outbox, retry, DLQ, idempotência ✅

**6.1 Tabela/entidade outbox**
- `V3__create_outbox_event_table.sql`: `tb_outbox_event(id, event_id UUID, aggregate_id, event_type, payload, status, attempts, created_at, sent_at)` + `UNIQUE` em `event_id` + índice em `status`.
- `OutboxEvent` + `OutboxStatus` (`PENDING, SENT, FAILED`) + `OutboxEventRepository`.

**6.2 Produtor transacional**
- `OrderEventDTO`: + `eventId` (UUID), `version`.
- `OrderService.createOrder`: salva `Order` + `OutboxEvent` na mesma transação local; remove a chamada direta a `rabbitTemplate.convertAndSend`.

**6.3 Poller assíncrono** (dois beans, evita self-invocation `@Scheduled`+`@Transactional`):
- `OutboxDispatcher` (`@Transactional` por evento): publica, marca `SENT`/incrementa `attempts` até `FAILED` em 10 tentativas.
- `OutboxPoller` (`@Scheduled(fixedDelay = 5000)`): busca até 50 `PENDING`.

**6.4 Config RabbitMQ (produtor)**: `publisher-confirm-type: correlated`, `publisher-returns: true`, `template.retry`.

**6.5 DLQ + idempotência no notification-service**
- Dead-letter exchange/queue (`order.dlx`, `order.created.dlq`).
- `listener.simple.retry` + `default-requeue-rejected: false`.
- `IdempotencyGuard` em memória (limitação conhecida: não sobrevive a restart; melhoria futura seria tabela `processed_event`).

---

## FASE 7 — Fechar rede + remover defaults inseguros ✅

- `docker-compose.yml`: remover `ports:` de `product-service`, `inventory-service`, `notification-service`, `order-service`, `auth-service`, `eureka-server`. Só `api-gateway` e a infra continuam publicados.
- Remover defaults hardcoded de `DB_PASSWORD`/`JWT_SECRET` nos `application.yml`.
- `api-gateway/pom.xml`: adicionar `spring-dotenv`.

---

## Validação final das Fases 6 e 7 (04/09/2026)

- Os sete módulos compilam e `docker compose config --quiet` foi aprovado.
- A migration `V3` foi aplicada no banco do Order Service e criou `tb_outbox_event`.
- Pedido criado com RabbitMQ disponível: Outbox passou para `SENT`, fila foi consumida e a notificação registrou `eventId` e `version`.
- Pedido criado com RabbitMQ parado: API retornou `201`, evento permaneceu `PENDING` e foi publicado/consumido após o broker voltar.
- Mensagem inválida percorreu as tentativas configuradas e chegou a `order.created.dlq`.
- Republicação do mesmo `eventId` foi identificada e ignorada pelo consumidor.
- As portas 8081, 8082, 8083, 8084, 8086 e 8761 deixaram de responder no host; o fluxo pelo gateway em 8080 continuou funcional.
- Os defaults de `DB_PASSWORD` e `JWT_SECRET` foram removidos e as imagens antes marcadas como `latest` foram fixadas por digest.
- A configuração `spring.flyway` de Auth, Inventory e Order foi corrigida para o nível esperado pelo Spring Boot.

---

## Notas de implementação (problemas reais encontrados durante a execução)

Estes dois problemas não estavam previstos no plano original — foram descobertos e corrigidos durante a validação manual das Fases 3/4 e 4/5:

**1. Nome de coluna `userID` → `userid` (não `user_id`)**
A estratégia de nomenclatura padrão do Spring Boot (`SpringPhysicalNamingStrategy`) só insere `_` antes de uma letra maiúscula quando a letra **seguinte** também é minúscula. Como `userID` termina em duas maiúsculas consecutivas (`ID`), nenhum underscore é inserido e o nome vira `userid`. A migration `V1__baseline.sql` do order-service precisou ser corrigida de `user_id` para `userid` para bater com o que o Hibernate realmente valida.

**2. 403 mascarando o erro real quando não há `@ExceptionHandler`**
Antes da Fase 5, uma exceção não tratada que saía do controller fazia o Tomcat redirecionar internamente para `/error`. Esse redirecionamento roda a cadeia de filtros do Spring Security de novo — mas o `OrderSecurityFilter` (um `OncePerRequestFilter`) é pulado nesse redirecionamento por padrão, então o contexto de autenticação já não estava mais lá. Como `/error` não estava liberado no `SecurityConfig`, a segunda passada negava acesso e devolvia **403** em vez do 500 real, mascarando completamente a causa do erro. Resolvido definitivamente pela Fase 5 (a exceção passa a ser resolvida dentro do próprio dispatch do Spring MVC, nunca chega no redirecionamento para `/error`) e reforçado liberando `/error` no `SecurityConfig`.

---

## Arquivos críticos (referência rápida)

- `order-service/.../services/OrderService.java` — coração das Fases 2, 4, 6.
- `order-service/.../entities/Order.java` e `OrderItem.java`
- `inventory-service/.../services/InventoryService.java` e `InventoryRepository.java`
- `auth-service/.../infra/security/SecurityFilter.java` e `TokenService.java` — modelo de referência da Fase 2.2
- `inventory-service/.../db/migration/V1__create_inventory_table.sql` e `application.yml` — modelo de referência Flyway das Fases 1 e 3
- `docker-compose.yml` — Fases 1, 2, 7
- `notification-service/.../config/RabbitMQConfig.java` — Fase 6.5

## Verificação (a cada fase, antes de seguir para a próxima)

1. `mvn -pl <serviço> -am compile` — cada fase deve compilar sem quebrar os demais módulos.
2. Resetar volumes/tabelas de banco quando a fase alterar schema (Fases 1, 3, 4, 6).
3. `docker compose up --build` e conferir `docker compose ps` — todos os serviços tocados devem chegar a `healthy`.
4. Fluxo manual via gateway (`http://localhost:8080`) a cada fase relevante — ver histórico de testes já rodados (registro/login, criação de produto/estoque, criação de pedido, ownership, estoque insuficiente, payloads inválidos) confirmando os status HTTP corretos.
5. Fase 6: derrubar o RabbitMQ momentaneamente durante a criação de um pedido, subir de novo, confirmar que o poller publica sem perda; mensagens rejeitadas repetidamente devem ir para a DLQ. **Validado em 04/09/2026.**
6. Fase 7: confirmar que `curl http://localhost:8081/actuator/health` (direto, sem gateway) deixa de responder, mas o fluxo completo via `localhost:8080` continua funcionando. **Validado em 04/09/2026.**
