# Status do projeto

**Branch:** `main`

O P0 do MVP (identidade via JWT, estoque atômico, eventos confiáveis via Outbox/DLQ/idempotência, isolamento de rede) está concluído. Este arquivo lista apenas o que ainda falta.

## Backlog

### Testes

- [ ] Testes unitários para regras de Auth, Inventory e Order.
- [ ] Testes de controller para autenticação, autorização e validação.
- [ ] Testes de integração isolados com Testcontainers ou perfil dedicado.
- [ ] Teste ponta a ponta pelo Gateway.

### Order

- [ ] Transições válidas do status do pedido (status já é texto correto; falta validar transição entre estados e criar endpoint de mudança de status).
- [ ] Timeouts, circuit breakers, retry e fallbacks para as chamadas Feign (Product/Inventory).

### Observabilidade

- [ ] Adicionar auth-service ao scrape do Prometheus.
- [ ] Criar dashboards básicos no Grafana.

### Demonstração

- [ ] Criar seeds reproduzíveis para demonstração/apresentação do TCC.

### Qualidade e evolução

- [ ] Paginar listagens de produtos e pedidos.
- [ ] Unificar padrões de DTO, injeção de dependência, exceptions e logging entre os serviços.
- [ ] Publicar Swagger/OpenAPI de forma acessível pelo gateway.
- [ ] Criar diagramas de arquitetura, sequência do pedido e modelo de dados.
- [ ] Definir retenção de logs, métricas e traces.
- [ ] Criar pipeline de CI.
- [ ] Idempotência do notification-service persistente (hoje é em memória, não sobrevive a restart nem escala para múltiplas instâncias).

### Decisões em aberto

- [ ] Decidir se IA faz parte da entrega final; se sim, criar `ai-service` só depois do MVP estável.
- [ ] Decidir formalmente se haverá Payment Service.
