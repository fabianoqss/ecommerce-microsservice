# Status do projeto

**Última auditoria:** 19 de agosto de 2026  
**Branch:** `main`  
**Estado geral:** protótipo funcional; ainda não pronto para entrega final

## Resumo executivo

A base técnica está montada e demonstra os principais elementos propostos para o TCC: microsserviços Spring Boot, descoberta por Eureka, gateway, JWT, bancos distintos, chamadas síncronas, mensageria e observabilidade.

O principal bloqueio para considerar o sistema concluído não é a criação de mais serviços. É tornar o fluxo de pedido íntegro, seguro, testável e reproduzível. Hoje o pedido verifica estoque, mas não o reserva nem reduz; aceita a identidade enviada pelo cliente; e os serviços podem ser acessados diretamente, contornando o gateway.

## Estado por componente

| Componente | Implementado | Falta para concluir |
|---|---|---|
| Eureka | Registro e descoberta | Teste de integração e configuração mais resiliente |
| Gateway | Rotas, CORS, JWT, roles | Fechar acesso direto aos serviços, testes de autorização e documentação agregada |
| Auth | Cadastro, login, BCrypt, JWT, roles, validações básicas | E-mail único no banco, migrations, remover credenciais fixas e testes isolados |
| Product | CRUD, validação, MongoDB, Swagger | Paginação, testes e revisão da exposição direta |
| Inventory | Consulta, inclusão, atualização e Flyway | Reserva/baixa atômica, concorrência, erros padronizados e testes |
| Order | Criação, cálculo, persistência, consultas, Feign e evento | Identidade pelo JWT, baixa de estoque, validação, status, ownership, resiliência e Outbox |
| Notification | Consumo RabbitMQ e conversão JSON | Notificação real, logging estruturado, retry/DLQ e idempotência |
| Observabilidade | Actuator, Prometheus, Grafana e Zipkin | Coletar Auth, provisionar dashboards, alertas e validar traces completos |
| Docker Compose | Serviços e dependências declarados; sintaxe válida | Executar e documentar teste ponta a ponta; fixar versões de imagens |

## Validação realizada

- `docker compose config --quiet`: aprovado.
- Docker Engine disponível durante a auditoria.
- Nenhum container do Compose estava em execução no momento da inspeção.
- Os sete testes Maven existentes passaram.
- Todos os testes são apenas `contextLoads()`.
- O teste de Auth usou PostgreSQL local e executou criação do administrador; não está isolado.
- Não foi realizado `docker compose up --build` completo durante a auditoria.

## Backlog priorizado

### P0 — obrigatório para um MVP correto

- [ ] Derivar usuário/e-mail do JWT no Order Service; remover `userId` controlado pelo cliente.
- [ ] Restringir listagem e consulta de pedidos ao proprietário, com exceção explícita para administrador.
- [ ] Implementar reserva ou baixa atômica de estoque.
- [ ] Evitar overselling em requisições concorrentes.
- [ ] Tornar banco + publicação de evento confiáveis com Outbox ou mecanismo equivalente.
- [ ] Impedir acesso externo direto às APIs internas ou validar JWT também nelas.
- [ ] Validar itens de pedido, quantidades, listas vazias e campos obrigatórios.
- [ ] Padronizar respostas de erro e códigos HTTP em Order e Inventory.
- [ ] Remover credenciais fixas e defaults sensíveis do código.

### P1 — obrigatório para entrega e manutenção

- [ ] Criar testes unitários para regras de Auth, Inventory e Order.
- [ ] Criar testes de controller para autenticação, autorização e validação.
- [ ] Criar testes de integração isolados com Testcontainers ou perfil dedicado.
- [ ] Criar teste ponta a ponta pelo Gateway.
- [ ] Adotar Flyway em Auth e Order.
- [ ] Adicionar `UNIQUE` para e-mail de usuário no schema.
- [ ] Persistir o nome do produto no item do pedido ou resolver o contrato de resposta; consultas atuais retornam nome nulo.
- [ ] Implementar transições válidas do status do pedido.
- [ ] Configurar timeouts, circuit breakers, retry e fallbacks para Feign.
- [ ] Adicionar retry, Dead Letter Queue e idempotência no consumidor RabbitMQ.
- [ ] Adicionar auth-service ao scrape do Prometheus.
- [ ] Criar dashboards básicos no Grafana.
- [ ] Fixar versões das imagens `latest`.
- [ ] Criar seeds reproduzíveis para demonstração.
- [ ] Validar `docker compose up --build` partindo de ambiente limpo.

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
- [ ] Decidir se haverá frontend ou apenas demonstração por Swagger/Postman.

## Riscos conhecidos

| Risco | Impacto |
|---|---|
| Pedido não reduz estoque | Venda ilimitada do mesmo item |
| Verificação e atualização não atômicas | Overselling sob concorrência |
| `userId` fornecido no payload | Pedido criado em nome de terceiro |
| GET de todos os pedidos para qualquer autenticado | Exposição de dados |
| Portas internas expostas | Bypass das regras do gateway |
| Save no banco antes da publicação RabbitMQ | Pedido persistido sem evento |
| Credencial de admin fixa | Acesso privilegiado previsível |
| `ddl-auto=update` | Schema não versionado e implantação imprevisível |
| Testes ligados ao ambiente local | Resultado não reproduzível e alteração de dados reais |
| Imagens Docker com `latest` | Builds futuros não determinísticos |

## Critério de conclusão do MVP

O MVP pode ser considerado concluído quando:

- [ ] todo acesso externo passa pelo gateway;
- [ ] autenticação e autorização são comprovadas por testes;
- [ ] pedido pertence ao usuário autenticado;
- [ ] estoque é reduzido/reservado sem condição de corrida;
- [ ] falhas entre serviços retornam erros controlados;
- [ ] evento de pedido é entregue de forma confiável e idempotente;
- [ ] schemas são versionados;
- [ ] o ambiente sobe do zero com um único comando documentado;
- [ ] existe massa de demonstração reproduzível;
- [ ] o fluxo cadastro -> login -> catálogo -> estoque -> pedido -> evento passa ponta a ponta;
- [ ] documentação e diagramas refletem a implementação entregue.

## Ordem recomendada de execução

1. Segurança e identidade do pedido.
2. Reserva/baixa de estoque e controle de concorrência.
3. Consistência de eventos e tratamento de falhas.
4. Validação, erros e transições de status.
5. Migrações e remoção de credenciais fixas.
6. Testes unitários, integração e ponta a ponta.
7. Compose limpo, seeds e observabilidade.
8. Documentação final e apresentação do TCC.
9. Somente então avaliar IA, pagamento ou frontend.

## Estado do Git durante a auditoria

Já existia uma alteração local não commitada em `docker-compose.yml`, corrigindo as variáveis de endpoint Zipkin de Order e Notification para `MANAGEMENT_TRACING_EXPORT_ZIPKIN_ENDPOINT`. A correção é coerente com a configuração Spring Boot atual e deve ser preservada e revisada para commit.
