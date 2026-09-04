# Status do projeto

**Última auditoria:** 19 de agosto de 2026  
**Última atualização:** 4 de setembro de 2026
**Branch:** `main`  
**Estado geral:** Fases 1–7 concluídas; P0 de identidade, estoque, eventos e isolamento de rede resolvido

Plano de execução detalhado, fase a fase: [`docs/PLANO_EXECUCAO.md`](docs/PLANO_EXECUCAO.md).

## Resumo executivo

A base técnica está montada e demonstra os principais elementos propostos para o TCC: microsserviços Spring Boot, descoberta por Eureka, gateway, JWT, bancos distintos, chamadas síncronas, mensageria e observabilidade.

Desde a auditoria de 19/08, os maiores riscos do fluxo de pedido foram corrigidos e validados manualmente ponta a ponta pelo gateway: o pedido agora deriva o dono do JWT (não mais do body), reserva/baixa estoque de forma atômica (sem overselling comprovado em teste), preenche o nome do produto no item, e retorna erros padronizados (`ProblemDetail`) com os códigos HTTP corretos em vez de 500 genérico. Auth e Order também adotaram Flyway, e o admin de demonstração deixou de ser uma credencial fixa no código.

As Fases 6 e 7 concluíram o fechamento do P0: pedido e evento agora são persistidos na mesma transação por Outbox, a publicação aguarda confirmação do RabbitMQ, o consumidor possui retry, DLQ e proteção idempotente em memória, e os serviços internos deixaram de publicar portas no host. As pendências restantes são principalmente testes automatizados, resiliência das chamadas Feign, observabilidade final e massa de demonstração reproduzível.

## Estado por componente

| Componente | Implementado | Falta para concluir |
|---|---|---|
| Eureka | Registro e descoberta | Teste de integração e configuração mais resiliente |
| Gateway | Rotas, JWT, roles e acesso externo centralizado (CORS de frontend removido — não haverá frontend) | Testes de autorização e documentação agregada |
| Auth | Cadastro, login, BCrypt, JWT, roles, validações, **Flyway + e-mail único**, **admin via `ADMIN_EMAIL`/`ADMIN_PASSWORD`** (sem credencial fixa) | Testes isolados |
| Product | CRUD, validação, MongoDB, Swagger | Paginação, testes e revisão da exposição direta |
| Inventory | Consulta, inclusão, atualização, Flyway, **reserva/baixa atômica de estoque (`/reserve`, `/release`)**, **erros padronizados (`ProblemDetail`)** | Testes; validação local de JWT (opcional, fora de escopo por ora) |
| Order | Criação, cálculo, persistência, consultas, Feign, identidade via JWT, ownership, Flyway, estoque atômico com compensação, snapshot do produto, erros padronizados e **Outbox com confirmação do broker** | Resiliência Feign, transições completas de status e testes |
| Notification | Consumo RabbitMQ, logging estruturado, **retry, DLQ e idempotência por `eventId` em memória** | Notificação real e idempotência persistente para múltiplas instâncias/restarts |
| Observabilidade | Actuator, Prometheus, Grafana e Zipkin | Coletar Auth, provisionar dashboards, alertas e validar traces completos |
| Docker Compose | Serviços e dependências declarados; ambiente validado; **somente gateway e infraestrutura publicam portas; imagens de monitoramento fixadas por digest** | Refinar quais portas de infraestrutura precisam permanecer públicas |

## Validação realizada

### Auditoria original (19/08/2026)
- `docker compose config --quiet`: aprovado.
- Docker Engine disponível durante a auditoria.
- Nenhum container do Compose estava em execução no momento da inspeção.
- Os sete testes Maven existentes passaram.
- Todos os testes são apenas `contextLoads()` (segue verdadeiro — nenhum teste automatizado novo foi criado nas Fases 1-5).
- O teste de Auth usou PostgreSQL local e executou criação do administrador; não está isolado.
- Não foi realizado `docker compose up --build` completo durante a auditoria.

### Validação manual das Fases 1-5 (04/09/2026)
- `docker compose up --build` completo (13 containers) partindo de volume zerado: todos chegaram a `healthy`.
- Fluxo ponta a ponta pelo gateway (`localhost:8080`): registro, login (admin e dois usuários), criação de produto e estoque como admin, criação de pedido como usuário comum.
- Estoque decrementado corretamente (3 → 1 após pedido de 2 unidades); pedido com 5 unidades (estoque insuficiente) rejeitado com **409**, estoque permaneceu inalterado (sem overselling).
- Usuário B tentando ver pedido do usuário A → **403**; usuário A vê só os próprios pedidos; admin vê todos.
- Pedido inexistente → **404**; produto inexistente no pedido → **404**; payload inválido (lista de itens vazia) → **400** com detalhe do campo.
- Todas as respostas de erro no formato `ProblemDetail` (RFC 7807), sem 500 genérico nem vazamento de stacktrace.
- Dois problemas reais encontrados e corrigidos durante essa validação (nome de coluna `userID`→`userid` e um 403 mascarando erros não tratados) — detalhados em [`docs/PLANO_EXECUCAO.md`](docs/PLANO_EXECUCAO.md#notas-de-implementação-problemas-reais-encontrados-durante-a-execução).

### Validação manual das Fases 6–7 (04/09/2026)
- Migration V3 da Outbox aplicada e validada no PostgreSQL.
- Pedido com RabbitMQ disponível produziu evento `SENT` e notificação consumida.
- Com RabbitMQ parado, o pedido continuou retornando **201**, o evento ficou `PENDING` e foi entregue automaticamente após a recuperação do broker.
- Mensagem inválida percorreu o retry e chegou à DLQ; republicação de um `eventId` processado foi ignorada.
- Todos os 13 containers ficaram ativos; serviços com healthcheck ficaram `healthy`.
- Portas 8081, 8082, 8083, 8084, 8086 e 8761 foram verificadas como inacessíveis pelo host; o gateway permaneceu acessível em 8080.
- Os testes `contextLoads()` continuam dependentes de configuração/infraestrutura local: Gateway e Notification passaram quando configurados; os testes JPA não conseguem acessar de forma isolada o PostgreSQL do Compose a partir do host. Isso permanece no backlog de testes, fora das Fases 1–7.

## Backlog priorizado

### P0 — obrigatório para um MVP correto

- [x] Derivar usuário/e-mail do JWT no Order Service; remover `userId` controlado pelo cliente. *(Fase 2)*
- [x] Restringir listagem e consulta de pedidos ao proprietário, com exceção explícita para administrador. *(Fase 2)*
- [x] Implementar reserva ou baixa atômica de estoque. *(Fase 4)*
- [x] Evitar overselling em requisições concorrentes. *(Fase 4 — `UPDATE ... WHERE quantity >= :qty`, validado manualmente)*
- [x] Tornar banco + publicação de evento confiáveis com Outbox ou mecanismo equivalente. *(Fase 6)*
- [x] Impedir acesso externo direto às APIs internas ou validar JWT também nelas. *(Fase 7; order-service também valida JWT localmente)*
- [x] Validar itens de pedido, quantidades, listas vazias e campos obrigatórios. *(Fase 2)*
- [x] Padronizar respostas de erro e códigos HTTP em Order e Inventory. *(Fase 5)*
- [x] Remover credenciais fixas e defaults sensíveis do código. *(Fases 1 e 7)*

### P1 — obrigatório para entrega e manutenção

- [ ] Criar testes unitários para regras de Auth, Inventory e Order.
- [ ] Criar testes de controller para autenticação, autorização e validação.
- [ ] Criar testes de integração isolados com Testcontainers ou perfil dedicado.
- [ ] Criar teste ponta a ponta pelo Gateway.
- [x] Adotar Flyway em Auth e Order. *(Fases 1 e 3)*
- [x] Adicionar `UNIQUE` para e-mail de usuário no schema. *(Fase 1)*
- [x] Persistir o nome do produto no item do pedido ou resolver o contrato de resposta; consultas atuais retornam nome nulo. *(Fase 4 — corrigido em `GET /api/order` e `GET /api/order/{id}`, não só na criação)*
- [ ] Implementar transições válidas do status do pedido. *(status já é texto com valores corretos desde a Fase 4; validação de transição entre estados — ex. não permitir `COMPLETED` → `PENDING` — ainda não implementada, não há endpoint de mudança de status ainda)*
- [ ] Configurar timeouts, circuit breakers, retry e fallbacks para Feign.
- [x] Adicionar retry, Dead Letter Queue e idempotência no consumidor RabbitMQ. *(Fase 6; idempotência em memória)*
- [ ] Adicionar auth-service ao scrape do Prometheus.
- [ ] Criar dashboards básicos no Grafana.
- [x] Fixar versões das imagens `latest` por digest.
- [ ] Criar seeds reproduzíveis para demonstração.
- [x] Validar `docker compose up --build` partindo de ambiente limpo. *(validado nesta sessão — 04/09/2026)*

### P2 — qualidade e evolução

- [ ] Paginar listagens de produtos e pedidos.
- [ ] Unificar padrões de DTO, injeção de dependência, exceptions e logging.
- [ ] Publicar Swagger/OpenAPI de forma acessível pelo gateway.
- [ ] Criar diagramas de arquitetura, sequência do pedido e modelo de dados.
- [ ] Definir retenção de logs, métricas e traces.
- [ ] Criar pipeline de CI.

### Escopo opcional ou ainda não iniciado

- [ ] Decidir se IA faz parte da entrega final.
- [ ] Se aprovada, criar `ai-service` somente depois do MVP estável.
- [ ] Decidir formalmente se haverá Payment Service; ele aparece apenas em documentação antiga.
- [x] Decidir se haverá frontend: **não haverá** — demonstração será via Swagger/Postman.

## Riscos conhecidos

| Risco | Impacto | Status |
|---|---|---|
| Pedido não reduz estoque | Venda ilimitada do mesmo item | ✅ Resolvido (Fase 4) |
| Verificação e atualização não atômicas | Overselling sob concorrência | ✅ Resolvido (Fase 4, `UPDATE` condicional) |
| `userId` fornecido no payload | Pedido criado em nome de terceiro | ✅ Resolvido (Fase 2) |
| GET de todos os pedidos para qualquer autenticado | Exposição de dados | ✅ Resolvido (Fase 2) |
| Portas internas expostas | Bypass das regras do gateway | ✅ Resolvido (Fase 7) |
| Save no banco antes da publicação RabbitMQ | Pedido persistido sem evento | ✅ Resolvido (Fase 6 — Outbox) |
| Credencial de admin fixa | Acesso privilegiado previsível | ✅ Resolvido (Fase 1, via `ADMIN_EMAIL`/`ADMIN_PASSWORD`) |
| `ddl-auto=update` em Auth e Order | Schema não versionado e implantação imprevisível | ✅ Resolvido (Fases 1 e 3, Flyway) |
| Testes ligados ao ambiente local | Resultado não reproduzível e alteração de dados reais | ⏳ Pendente (fora do escopo das Fases 1-7) |
| Imagens Docker com `latest` | Builds futuros não determinísticos | ✅ Resolvido para Zipkin, Prometheus e Grafana (digest fixo) |

## Critério de conclusão do MVP

O MVP pode ser considerado concluído quando:

- [x] todo acesso externo aos microsserviços passa pelo gateway; *(Fase 7)*
- [ ] autenticação e autorização são comprovadas por testes; *(validadas manualmente, sem testes automatizados ainda)*
- [x] pedido pertence ao usuário autenticado;
- [x] estoque é reduzido/reservado sem condição de corrida;
- [x] falhas entre serviços retornam erros controlados;
- [x] evento de pedido é entregue de forma confiável e idempotente; *(Fase 6; idempotência em memória, adequada ao deployment atual de uma instância)*
- [x] schemas são versionados; *(Auth, Order e Inventory via Flyway — Product usa MongoDB, sem migração aplicável)*
- [x] o ambiente sobe do zero com um único comando documentado; *(`docker compose up --build`, validado nesta sessão)*
- [ ] existe massa de demonstração reproduzível;
- [x] o fluxo cadastro -> login -> catálogo -> estoque -> pedido -> evento passa ponta a ponta; *(incluindo recuperação após indisponibilidade do broker)*
- [ ] criar diagramas finais de arquitetura e sequência para a apresentação. *(a documentação textual reflete a implementação)*

## Ordem recomendada de execução

1. ✅ Segurança e identidade do pedido.
2. ✅ Reserva/baixa de estoque e controle de concorrência.
3. ✅ Consistência de eventos e tratamento de falhas *(Fase 6)*.
4. ✅ Validação, erros e transições de status *(transições completas de status ainda não implementadas — ver P1)*.
5. ✅ Migrações e remoção de credenciais fixas e defaults sensíveis *(Fases 1 e 7)*.
6. Testes unitários, integração e ponta a ponta.
7. ✅ Compose com rede externa fechada *(Fase 7)*; seeds e observabilidade ainda pendentes.
8. Documentação final e apresentação do TCC.
9. Somente então avaliar IA, pagamento ou frontend *(frontend descartado definitivamente)*.

Detalhamento técnico de cada fase: [`docs/PLANO_EXECUCAO.md`](docs/PLANO_EXECUCAO.md).

## Estado do Git

A correção de `docker-compose.yml` mencionada na auditoria de 19/08 foi commitada junto da Fase 1 (`e9122e3`). As Fases 2–7 (identidade JWT, estoque atômico, erros padronizados, Outbox/DLQ/idempotência e isolamento de rede) foram commitadas e enviadas para `origin/main` em `777d369`; a collection Bruno de testes de API foi commitada separadamente em `8bd8cb8`. `git status` está limpo — working tree sincronizado com o remoto.
