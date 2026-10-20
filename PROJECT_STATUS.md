# Status do projeto

O P0 do MVP foi concluído e a identidade foi migrada para Keycloak/OIDC, com BFF no gateway. O fluxo inclui estoque atômico, cancelamento de pedido, Outbox/DLQ e autorização por dono. A documentação operacional atual está no [README.md](README.md); o histórico do fechamento está em [docs/PLANO_EXECUCAO.md](docs/PLANO_EXECUCAO.md).

## Estado atual

- Seis módulos Java: Eureka, Gateway, Product, Inventory, Order e Notification.
- Gateway com sessão no Redis, Authorization Code com PKCE e suporte a Bearer; serviços de negócio validam JWT por JWKs, issuer e audience.
- Eureka com HTTP Basic; Actuator do gateway em porta interna própria; aplicações acessíveis externamente pelo gateway.
- RabbitMQ com vhost `ecommerce`, credenciais separadas por serviço e provisionamento via `rabbitmq-init`; Redis também persiste a idempotência de Notification.
- Compose da raiz como infraestrutura oficial; segredos locais gerados por `scripts/generate-env.sh` e configuração da IDE por `scripts/ide-env.sh`.
- Testes unitários, de autorização e de integração já existentes; listagem de pedidos paginada e cancelamento implementado.

## Backlog

### Testes

- [ ] Ampliar testes de regras de Inventory e do processamento de eventos, incluindo retry, DLQ e idempotência persistente.
- [ ] Ampliar testes de integração isolados com Testcontainers, cobrindo falhas e concorrência de Outbox e reservas.
- [ ] Automatizar o teste ponta a ponta pelo gateway, incluindo login, pedido, cancelamento e reinício com sessão persistida.

### Order

- [ ] Definir e implementar as demais transições do pedido além do cancelamento, conforme o escopo final do domínio.
- [ ] Revisar timeouts e políticas de resiliência nas chamadas Feign (Product/Inventory), respeitando a idempotência de cada operação.
- [ ] Implementar o contrato de [reservas idempotentes](docs/specs/reservas-idempotentes.md) antes de habilitar retries automáticos de reserva/liberação.

### Segurança e identidade (Keycloak)

- [ ] Versionar o realm `ecommerce` como código e criar seu teste de integração. Hoje a configuração manual pelo console não é reproduzível nem revisável em PR.
- [ ] Garantir por configuração/teste que o client público `ecommerce-dev` (PKCE, callback `https://oauth.usebruno.com/callback`) exista só em desenvolvimento.
- [ ] Validar upgrade de banco/realm existente para Keycloak `26.7.5` conforme as mudanças de migração `26.5`–`26.7`, além dos fluxos OIDC e das permissões dos clients.

### Observabilidade

- [ ] Criar dashboards básicos no Grafana.
- [ ] Definir retenção de logs, métricas e traces; revisar a amostragem de produção conforme carga e custo.

### Demonstração

- [ ] Criar seeds reproduzíveis para demonstração e apresentação do projeto.

### Qualidade e evolução

- [ ] Paginar a listagem de produtos.
- [ ] Unificar padrões de DTO, injeção de dependência, exceptions e logging entre os serviços.
- [ ] Publicar Swagger/OpenAPI de forma acessível pelo gateway.
- [ ] Criar diagramas de arquitetura, sequência do pedido e modelo de dados.
- [ ] Criar pipeline de CI.

### Decisões em aberto

- [ ] Decidir se IA faz parte da entrega final; se sim, criá-la só depois do MVP estável.
- [ ] Decidir formalmente se haverá Payment Service.
