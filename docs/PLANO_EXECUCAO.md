# Plano de execução — histórico do fechamento do P0

O P0 foi executado e validado em 04/09/2026. Este documento preserva os objetivos e as evidências daquele fechamento, com as decisões atuais de identidade e infraestrutura. As instruções operacionais estão no [README.md](../README.md), e as pendências no [PROJECT_STATUS.md](../PROJECT_STATUS.md).

## Resultado das sete fases

| Fase | Objetivo | Resultado |
|---|---|---|
| 1 | Migrações de banco, credenciais externas e limpeza de CORS | Concluída; identidade posteriormente delegada ao Keycloak |
| 2 | Identidade do pedido e autorização por dono | Concluída; atualmente usa `sub` do Keycloak e validação OIDC |
| 3 | Flyway no Order | Concluída; schema validado pelo Hibernate |
| 4 | Estoque atômico, snapshot do produto e status textual | Concluída |
| 5 | Validação e erros padronizados | Concluída |
| 6 | Outbox, retry, DLQ e idempotência | Concluída; idempotência de Notification posteriormente persistida no Redis |
| 7 | Isolamento de rede e remoção de credenciais padrão | Concluída; reforçada com credenciais por serviço e Eureka autenticado |

## Decisões atuais

### Identidade e autorização

- O Keycloak é o emissor de identidade. Cadastro, login e recuperação de senha pertencem ao realm `ecommerce`.
- O gateway oferece BFF com Authorization Code e PKCE, sessões no Redis e token relay; também aceita Bearer token sem sessão.
- Product, Inventory e Order validam assinatura por JWKs, issuer e audience, além das roles/scopes exigidos em cada endpoint.
- A identidade persistida em `Order.userid` é o `sub` do Keycloak. Um usuário vê seus próprios pedidos; administradores têm acesso ampliado. Consultas a pedidos de outro usuário não revelam a existência do recurso.
- Reserve/release de estoque são contratos internos. O gateway bloqueia essas rotas; Order usa `client_credentials` com scope `inventory:reserve` para acessar Inventory diretamente.

### Pedido, estoque e eventos

- A reserva usa atualização condicional de quantidade dentro de transação no Inventory; uma falha no lote desfaz os decrementos locais.
- A compensação libera a reserva quando a criação do pedido falha depois da confirmação remota. A idempotência de reserva/liberação ainda está especificada como evolução, e deve preceder retries automáticos dessas chamadas.
- O pedido armazena snapshot do nome do produto e status como texto. Sem pagamento no escopo atual, o pedido nasce confirmado.
- Cancelar um pedido confirmado devolve o estoque reservado. Repetir o cancelamento não deve liberar estoque novamente.
- Pedido e evento Outbox são persistidos na mesma transação local. O poller publica eventos pendentes com confirmação do broker e controle de tentativas.
- Notification aplica retry, dead lettering e deduplicação com Redis. A notificação continua sendo registrada no log.

### Infraestrutura e operação

- Há seis módulos Java: Eureka, Gateway, Product, Inventory, Order e Notification. O Compose da raiz é o ponto de entrada oficial, com override de desenvolvimento para PostgreSQL, MongoDB e Mailpit.
- O profile `apps` reúne os seis módulos; `monitoring` reúne Zipkin, Prometheus e Grafana. Keycloak, RabbitMQ, o provisionador `rabbitmq-init` e Redis não dependem desses profiles.
- O `.env.example` da raiz documenta o contrato de configuração. `scripts/generate-env.sh` gera segredos distintos; `scripts/ide-env.sh` prepara as variáveis dos seis módulos para execução local.
- Order e Inventory usam `DB_PASSWORD` do PostgreSQL local em desenvolvimento; produção exige usuários e credenciais próprios por serviço.
- RabbitMQ tem vhost `ecommerce` e usuários próprios para Order e Notification, limitados aos recursos `^order\.`. Credenciais administrativas ficam restritas ao console e ao provisionamento.
- Eureka usa HTTP Basic sem fallback de senha. Somente o gateway publica a API da aplicação; sua porta Actuator `8090` permanece interna.
- Cookies de sessão são seguros por padrão em produção, com override para HTTP local. Traces usam amostragem `1.0` em desenvolvimento e `0.1` por padrão em produção.

## Evidências históricas de 04/09/2026

Os itens abaixo registram a validação daquela data; não substituem uma nova execução após alterações de código, configuração ou dependências.

- Os módulos então existentes compilaram e `docker compose config --quiet` foi aprovado.
- A migration `V3` criou `tb_outbox_event` no banco do Order.
- Com RabbitMQ disponível, o pedido gerou evento `SENT` na Outbox e a notificação registrou `eventId` e `version`.
- Com RabbitMQ parado, a API retornou `201`, o evento permaneceu `PENDING` e foi entregue após o broker voltar.
- Mensagens inválidas percorreram as tentativas e chegaram a `order.created.dlq`.
- Republicar o mesmo `eventId` não repetiu a notificação.
- As portas dos serviços internos deixaram de responder no host, preservando o fluxo pelo gateway.
- Foram removidos defaults inseguros de credenciais e corrigido o posicionamento da configuração Flyway.

## Notas de implementação preservadas

### Coluna de identidade do pedido

A coluna criada pela migration original é `userid`, correspondente ao campo Java `userID`. A migration e o mapeamento precisam permanecer coerentes. O valor hoje é o identificador estável `sub` do Keycloak; bases antigas com e-mails como identidade exigem uma migração explícita antes de reutilizar pedidos.

### Erros mascarados pela segurança

Uma exceção não tratada pode provocar um dispatch para `/error`. Se esse caminho for bloqueado pela cadeia de segurança, o cliente pode receber um `403` que mascara a causa original. Os handlers com `ProblemDetail` resolvem os erros conhecidos no próprio dispatch MVC; a configuração de segurança também libera `/error`.

## Validação para próximas alterações

1. Executar os testes dos módulos afetados pelo Maven Wrapper; testes Testcontainers exigem Docker acessível.
2. Validar a configuração Compose de produção e a combinação com o override local, usando ambiente completo sem expor seus segredos na saída compartilhada.
3. Aplicar migrations em banco de teste e verificar o schema; não resetar volumes com dados a preservar.
4. Exercitar pelo gateway: login BFF/PKCE, acesso Bearer, administração de catálogo/estoque, criação e consulta de pedido, ownership, cancelamento e payload inválido.
5. Exercitar a falha temporária do RabbitMQ, entrega posterior pela Outbox, retry/DLQ e deduplicação após reinício do consumidor.
6. Confirmar que reserve/release não são acessíveis pelo gateway, que Inventory exige o scope de serviço e que Eureka rejeita credenciais ausentes ou incorretas.
7. Confirmar que métricas do gateway usam a porta interna `8090` e que as portas internas não foram publicadas acidentalmente.
8. Para volumes RabbitMQ 3.x ou banco Keycloak anterior, seguir as notas de upgrade e preservação de dados do [README.md](../README.md) antes de iniciar as novas imagens.
