# Próximos passos (revisão de 2026-10-20)

Estado: branch `feat/keycloak-bff`, tudo commitado e enviado. Os 6 serviços estão em Spring Boot 4.0.8 /
Spring Cloud 2025.1.3 e todas as suítes passam. Achados abaixo já verificados no código; o que ainda
depende de verificação está marcado.

## Lote B — segurança e código morto (risco baixo)
- Apagar `product-service/infra/` (Mongo publicado em 0.0.0.0:27017 sem auth; o `docker-compose.dev.yml` da raiz já cobre).
- order-service: OAuth2 do Feign só no `InventoryClient` (hoje `spring.cloud.openfeign.oauth2.enabled` manda o token de serviço até para o product-service).
- Cookie de sessão do gateway com `Secure=true` por padrão no compose de produção (`false` só no override de dev).
- Limites: paginação com teto em `GET /api/product`; `@Size(max = 50)` na lista de itens do pedido.
- `sampling.probability` e `show-sql` por ambiente (hoje 1.0 e true em produção).
- `/actuator/prometheus` e `/actuator/info` do gateway fora do acesso público.
- notification-service: remover `OpenAPIConfig` + `springdoc` (não tem endpoint HTTP) e `spring-rabbit-test`; trocar o `IdempotencyGuard` em memória (cresce sem limite e perde estado no restart) por inbox persistente.
- api-gateway: unir as rotas `-root` duplicadas no `application.yml`; remover `micrometer-tracing-bridge-brave` (transitivo) e import morto em `SessionChainTest`.
- Construtores mortos: `Product(6 args)`, `Inventory(3 args)`.
- Config quebrada: `open-in-view` do inventory está em `spring.jpa.properties.hibernate` (ignorado; mover para `spring.jpa.open-in-view: false`); chave antiga do Zipkin em order/notification (`management.zipkin.tracing.endpoint` -> `management.tracing.export.zipkin.endpoint`); `spring.application.name` ausente no eureka-server.
- NÃO mudar: `spring.mongodb.uri` é a propriedade correta no Boot 4 (`spring.data.mongodb.uri` está obsoleta, nível error). `spring-dotenv` é usado em runtime para rodar pela IDE.

## Lote C — infraestrutura (risco médio, exige subir o ambiente)
- RabbitMQ: um usuário por serviço com permissão mínima (hoje order e notification usam a credencial admin).
- Eureka com autenticação.
- Fixar versão/digest das imagens; sair do `rabbitmq:3-management` (linha 3.13 sem suporte).
- Keycloak 26.4.7: VERIFICAR os avisos citados na auditoria (GHSA-95cx-vmr5-3cmr, GHSA-j97h-3f8r-mrjr) e atualizar.
- README: o comando de geração de segredos não substitui os placeholders `troque-...` do `.env.example`.

## Lote D — documentação
- README, `PROJECT_STATUS.md`, `docs/PLANO_EXECUCAO.md` ainda citam o auth-service; 8 arquivos `.bru` dependem dele ou dizem que os serviços "não têm segurança".
- `.env.example` duplicados em order-service e inventory-service (o da raiz é o oficial).

## Lote E — estrutura (risco alto, um serviço por vez)
- POM agregador na raiz com versões centralizadas (`dependencyManagement`) e módulo `common-security` para o `KeycloakJwtAuthenticationConverter` (hoje copiado em 4 serviços). NÃO compartilhar DTOs de contrato entre serviços (acopla deploys); usar versionamento de evento.
- Pacotes padronizados: raiz `com.example.ecommerce.<serviço>`; `controller/ service/ domain/ repository/ dto/{request,response}/ exception/ config/ infra/{security,exception}/`; `ProblemDetail` em todos; YAML em todos.
- api-gateway por funcionalidade: `security/` (SecurityConfig, GatewayAuthorizationRules), `security/jwt/` (converter, absorvendo `KeycloakRoles`), `bff/` (controller, response, properties, client registration), `bff/relay/` (SessionTokenRelayFilter, BearerTokenRequestWrapper).
- Topologia RabbitMQ declarada em duplicidade em order e notification.

## Lote F — reservas de estoque idempotentes
Desenho completo, já revisado por segunda opinião: `docs/specs/reservas-idempotentes.md` (seguir a seção "Revisão v2").
Entregas: (1) livro de reservas no inventory com endpoints novos e testes de corrida com Testcontainers;
(2) criação com PENDING + reconciliador no order-service; (3) cancelamento por evento + transição dos pedidos LEGACY.
O `cancel` atual ainda chama o inventory dentro da transação de propósito: muda na entrega (3).
