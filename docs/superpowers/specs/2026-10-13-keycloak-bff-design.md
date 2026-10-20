# Design: autenticação com Keycloak, gateway BFF e Resource Servers

**Data:** 2026-10-13
**Status:** em revisão

## 1. Contexto e objetivo

Hoje o `auth-service` emite JWTs assinados com HMAC (`JWT_SECRET` compartilhado). O `api-gateway` e o `order-service` validam esses tokens com filtros escritos à mão; `product-service` e `inventory-service` não têm segurança nenhuma e dependem do isolamento de rede do Docker. Problemas concretos:

1. Qualquer serviço que conhece o segredo HMAC pode **emitir** tokens válidos (inclusive de ADMIN).
2. `product-service` e `inventory-service` aceitam qualquer requisição que chegue a eles (ex.: executando pela IDE, `POST localhost:8082/api/product` sem token).
3. `order-service` chama `POST /api/inventory/reserve|release` sem nenhuma credencial.
4. Validação JWT duplicada e manual em três serviços.
5. `sub` do token é o email (mutável).
6. Expiração calculada com `LocalDateTime.now()` + offset fixo `-03:00` (vale 7h em container UTC).
7. Sem refresh token, logout ou revogação.

**Objetivo:** delegar identidade a um IdP padrão de mercado (Keycloak, OAuth 2.0 / OpenID Connect), transformar cada serviço com API em Resource Server, autenticar chamadas serviço-a-serviço com Client Credentials e preparar o backend para um frontend Angular usando o padrão BFF recomendado pelo IETF (*OAuth 2.0 for Browser-Based Applications*).

**Critérios de sucesso:**

- Nenhum serviço contém código próprio de emissão ou validação de JWT.
- Toda rota de API de product, inventory e order rejeita requisições sem token válido conforme as regras da seção 5, mesmo acessadas diretamente (sem gateway).
- `/api/inventory/reserve` e `/release` só aceitam o token de serviço do `order-service`.
- O navegador nunca recebe access/refresh token; só um cookie de sessão `HttpOnly`.
- Restart do gateway não desloga usuários.
- Ambiente reproduzível: `docker compose up` sobe Keycloak já configurado a partir de arquivo versionado, sem cliques no console e sem segredos no Git.

## 2. Decisões tomadas

| Decisão | Escolha | Alternativas descartadas |
|---|---|---|
| Emissor de tokens | Keycloak (realm `ecommerce`) | auth-service com RS256+JWKS; Spring Authorization Server |
| Login de usuário | Authorization Code + PKCE | Password Grant (removido no OAuth 2.1) |
| Frontend | SPA Angular com **api-gateway como BFF** | SPA com client público (token no navegador) |
| Sessão do BFF | **Spring Session + Redis** | Sessão em memória |
| order → inventory | **Client Credentials** (scope `inventory:reserve`) | Token Relay do usuário |
| auth-service | **Removido** | Manter como serviço de perfil |
| Conversor de roles | Classe copiada em cada serviço | Módulo `security-commons` compartilhado |

## 3. Arquitetura

```
                 ┌───────────── mesmo domínio (sem CORS) ──────────────┐
 Navegador       │                                                     │
 Angular SPA  ──cookie SESSION──> api-gateway (BFF) ──Bearer──> product / inventory / order
                 │                  │      │                           │
                 │       login/logout      └── sessão + tokens ──> Redis
                 │                  ▼                                  │
                 │               Keycloak ──> Postgres (db keycloak)   │
                 └─────────────────────────────────────────────────────┘
 Bruno / app mobile ──Bearer──> api-gateway ──Bearer──> serviços
 order-service ──client_credentials──> Keycloak ; ──Bearer──> inventory
```

### 3.1 Componentes novos

- **Keycloak 26.4.7** (`quay.io/keycloak/keycloak:26.4.7`), modo `start-dev --import-realm` no ambiente local. Porta `8180` publicada em `127.0.0.1`. Usa o Postgres existente, database `keycloak` com usuário próprio `keycloak` (senha `KEYCLOAK_DB_PASSWORD`). Métricas habilitadas (`KC_METRICS_ENABLED=true`) e health habilitado (`KC_HEALTH_ENABLED=true`).
- **Redis 7.4** (`redis:7.4.11-alpine3.21`), senha obrigatória (`REDIS_PASSWORD`), porta `6379` publicada em `127.0.0.1` (para o gateway rodando na IDE), healthcheck com `redis-cli ping`, volume próprio.
- **`keycloak/realm-ecommerce.json`**: configuração do realm versionada. Valores sensíveis usam placeholders `${VAR}` substituídos pelo Keycloak no import a partir de variáveis de ambiente.
- **`init-db.sh`** substitui `init-db.sql`: cria os databases dos serviços e o database/usuário `keycloak` lendo a senha de variável de ambiente (SQL puro não lê env).

### 3.2 Componentes removidos

- Pasta `auth-service/`, serviço no `docker-compose.yml`, database `auth_service`, rotas `/auth/**` do gateway, pasta `bruno/auth-service/` e ambiente `AuthDirect.bru`.
- Variáveis `JWT_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` do `.env`/`.env.example`/compose.
- `JwtAuthenticationFilter` (gateway), `OrderSecurityFilter` e `TokenService` (order).
- Dependência `com.auth0:java-jwt` em todos os `pom.xml`.

### 3.3 Componentes alterados

| Serviço | Mudança |
|---|---|
| api-gateway | Resource Server (Bearer) **e** OAuth2 Client/BFF (sessão), Spring Session Redis, TokenRelay, CSRF, `/bff/me`, logout OIDC |
| order-service | Resource Server; OAuth2 Client (client_credentials) aplicado ao Feign |
| product-service | Resource Server (novo `spring-boot-starter-security`) |
| inventory-service | Resource Server (novo `spring-boot-starter-security`) |
| notification-service | Sem mudança de código; `userId` do evento passa a ser o `sub` (UUID) |
| eureka-server | Sem mudança |

## 4. Configuração do Keycloak (realm `ecommerce`)

### 4.1 Clients

| Client | Tipo | Fluxos | Observações |
|---|---|---|---|
| `ecommerce-bff` | confidencial | Authorization Code + PKCE (S256 obrigatório) | Secret `${KC_BFF_CLIENT_SECRET}`. Redirect URIs: `http://localhost:4200/login/oauth2/code/keycloak`, `http://localhost:8080/login/oauth2/code/keycloak`. Post-logout redirect: `http://localhost:4200/*`, `http://localhost:8080/*` |
| `ecommerce-dev` | público | Authorization Code + PKCE (S256 obrigatório) | Uso exclusivo do Bruno em desenvolvimento. Redirect URI do callback do Bruno |
| `order-service` | confidencial | Client Credentials (service account) | Secret `${KC_ORDER_SERVICE_CLIENT_SECRET}`. Client scope `inventory:reserve` atribuído como **default** |

Todos os clients: Direct Access Grant (Password Grant) **desligado**, Implicit **desligado**.

### 4.2 Roles, scopes e audience

> **Validado em 2026-10-13 (Keycloak 26.4.7):** quando o arquivo de import declara `clientScopes`, o Keycloak **não cria** os client scopes padrão (`basic`, `profile`, `email`, `roles`, `web-origins`, `acr`, `service_account`, `offline_access`). Sem eles os tokens saem sem `realm_access`, `email` e `name`. Por isso o `realm-ecommerce.json` declara explicitamente esses scopes (copiados da definição padrão da versão 26.4.7) além dos scopes próprios. O mapper de realm roles do scope `roles` também é configurado com `id.token.claim=true`, para o `/bff/me` ler as roles do ID token.
>
> O import usa a estratégia `IGNORE_EXISTING`: alterações no `realm-ecommerce.json` **não** são aplicadas a um realm que já existe no banco. Em desenvolvimento, aplicar mudanças exige recriar o database `keycloak`.

- Realm roles: `ADMIN`, `CUSTOMER`. `CUSTOMER` faz parte de `default-roles-ecommerce` (atribuída no auto-registro).
- Client scope `inventory:reserve` (emitido no claim `scope`), atribuído somente ao client `order-service`.
- Audience: client scope `ecommerce-api` com mapper *Audience* (`included.custom.audience = ecommerce-api`), default em todos os clients. Todos os Resource Servers exigem `aud` contendo `ecommerce-api`.

### 4.3 Usuários e políticas

- Usuário admin inicial: username/email `${KC_ADMIN_USER_EMAIL}`, senha `${KC_ADMIN_USER_PASSWORD}` (temporária = false), role `ADMIN`.
- Auto-registro: ligado. Login por email: ligado. Verificação de email: desligada (sem SMTP; registrado como pendência).
- Brute force detection: ligado.
- Password policy: `length(12) and notUsername and notEmail`.
- Tokens: access token 5 min; SSO session idle 30 min; SSO session max 10 h; refresh token com rotação (`revokeRefreshToken = true`).

### 4.4 Emissor (`iss`) consistente entre Docker e IDE

Problema: o `iss` do token reflete a URL do Keycloak vista pelo navegador (`http://localhost:8180`), mas containers só alcançam `http://keycloak:8080`.

Solução:

- Keycloak com `KC_HOSTNAME=http://localhost:8180` e `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`: o `iss` é sempre `http://localhost:8180/realms/ecommerce`, e chamadas back-channel pelo endereço interno são aceitas.
- Resource Servers configuram **ambos**: `issuer-uri` (só validação do claim `iss`) e `jwk-set-uri` apontando para `${KEYCLOAK_INTERNAL_URL}` (busca de chaves). Com `jwk-set-uri` presente, o Spring não faz discovery pelo `issuer-uri`.
- O client OAuth2 do gateway declara o provider explicitamente: `authorization-uri` com a URL pública (navegador), `token-uri`, `jwk-set-uri` e `user-info-uri` com a URL interna.
- Variáveis: `KEYCLOAK_ISSUER_URI` (padrão `http://localhost:8180/realms/ecommerce`) e `KEYCLOAK_INTERNAL_URL` (padrão `http://localhost:8180`; no compose `http://keycloak:8080`). Os padrões permitem rodar pela IDE sem configurar nada além dos segredos.

## 5. Resource Servers

Dependência: `spring-boot-starter-oauth2-resource-server`. Configuração comum:

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ecommerce}
  jwk-set-uri: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}/realms/ecommerce/protocol/openid-connect/certs
  audiences: ecommerce-api
  principal-claim-name: sub
```

**Conversor de autoridades** (`KeycloakJwtAuthenticationConverter`, copiado em cada serviço): combina `scope` → `SCOPE_<scope>` (comportamento padrão do Spring) com `realm_access.roles` → `ROLE_<role>`. Sessão `STATELESS`, CSRF desligado (só Bearer).

**Regras de autorização** (aplicadas no próprio serviço, independentemente do gateway):

| Serviço | Rota | Regra |
|---|---|---|
| product | `GET /api/product/**` | público |
| product | `POST`, `PUT`, `DELETE /api/product/**` | `ROLE_ADMIN` |
| inventory | `GET /api/inventory/**` | público |
| inventory | `POST /api/inventory/reserve`, `POST /api/inventory/release` | `SCOPE_inventory:reserve` |
| inventory | `POST /api/inventory`, `PUT /api/inventory/{id}` | `ROLE_ADMIN` |
| order | `/api/order/**` | autenticado; `CUSTOMER` vê/cria só os próprios pedidos, `ADMIN` vê todos |
| todos | `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus` | público (rede interna) |
| todos | demais `/actuator/**` | negado |
| todos | `/swagger-ui/**`, `/v3/api-docs/**` | público |

As regras de reserve/release vêm **antes** da regra genérica de `POST` de inventory.

### 5.1 order-service

- `Authentication#getName()` passa a ser o `sub` (UUID) do Keycloak. A coluna `userid` continua `VARCHAR(255)` e armazena o `sub`. Sem migration: o volume do Postgres foi recriado e não há pedidos com email a preservar.
- `createOrder` recebe o `sub` em vez do email.
- Client Credentials no Feign via suporte nativo do Spring Cloud OpenFeign:

  ```yaml
  spring.security.oauth2.client:
    registration.order-service:
      provider: keycloak
      client-id: order-service
      client-secret: ${KC_ORDER_SERVICE_CLIENT_SECRET}
      authorization-grant-type: client_credentials
    provider.keycloak.token-uri: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}/realms/ecommerce/protocol/openid-connect/token
  spring.cloud.openfeign.oauth2:
    enabled: true
    clientRegistrationId: order-service
  ```

  O token é obtido, guardado em cache e renovado pelo `OAuth2AuthorizedClientManager`. Ele vai também na chamada a `GET /api/product/{id}`, que é pública e aceita o token sem efeito.

## 6. api-gateway como BFF

Dependências novas: `spring-boot-starter-oauth2-client`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-security`, `spring-session-data-redis`, `spring-boot-starter-data-redis`.

### 6.1 Duas cadeias de segurança

1. **Cadeia Bearer** (`@Order(1)`, `securityMatcher` = requisição com header `Authorization: Bearer`): Resource Server com a mesma configuração da seção 5, `STATELESS`, CSRF desligado. O header é repassado ao serviço sem alteração.
2. **Cadeia de sessão** (`@Order(2)`, todo o resto):
   - `oauth2Login` com registration `keycloak` → client `ecommerce-bff`, scopes `openid profile email`, PKCE.
   - Login iniciado pelo frontend com navegação para `/oauth2/authorization/keycloak`. Após sucesso, redirect para `${FRONTEND_URL:http://localhost:4200}/`.
   - `/api/**` sem sessão → `401` (entry point `HttpStatusEntryPoint`), nunca redirect 302.
   - CSRF: `CookieCsrfTokenRepository.withHttpOnlyFalse()` + handler para SPA (padrão da documentação do Spring Security). Cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`. Exigido em `POST/PUT/PATCH/DELETE`.
   - `POST /logout`: invalida a sessão e executa RP-Initiated Logout (`OidcClientInitiatedLogoutSuccessHandler`) com retorno para `${FRONTEND_URL}/`.
   - `GET /bff/me`: retorna `{ sub, name, email, roles }` do `OidcUser`; `401` sem sessão.

Regras grossas aplicadas no gateway (as finas ficam nos serviços): `GET /api/product/**` e `GET /api/inventory/**` públicos; `/api/inventory/reserve` e `/release` **negados no gateway** (só serviço-a-serviço, pela rede interna); demais `/api/**` autenticados.

### 6.2 Rotas

- Rotas `/api/**` ganham `RemoveRequestHeader=Cookie` (o cookie de sessão não vaza para os serviços).
- **Relay do token da sessão:** filtro próprio `SessionTokenRelayFilter` (servlet filter executado depois do Spring Security). Só age quando a autenticação é `OAuth2AuthenticationToken` (cadeia de sessão) e a rota é `/api/**`: obtém o access token via `OAuth2AuthorizedClientManager` (renovando com o refresh token quando expira) e adiciona `Authorization: Bearer`. Requisições da cadeia Bearer passam intactas. Escolhido no lugar do filtro `TokenRelay` do Spring Cloud Gateway para eliminar a incerteza sobre a convivência com a cadeia Bearer e manter o comportamento testável isoladamente.
- Os clientes autorizados (tokens) ficam em `HttpSessionOAuth2AuthorizedClientRepository`, portanto dentro da sessão no Redis. O repositório padrão do Spring Boot guarda em memória e perderia os tokens num restart do gateway.
- A `ClientRegistration` do Keycloak é declarada em Java (não por propriedades) para poder informar `issuerUri` e `end_session_endpoint` sem discovery na subida — o discovery pelo `issuer-uri` público não funciona de dentro do Docker.
- Rotas `/auth/**` removidas.

### 6.3 Sessão

- Spring Session Redis, namespace `ecommerce:gateway:session`, timeout 30 min (alinhado ao SSO idle do Keycloak).
- Cookie `SESSION`: `HttpOnly`, `SameSite=Lax`, `Secure` controlado por `SESSION_COOKIE_SECURE` (padrão `false` para HTTP local; `true` em produção).
- `server.forward-headers-strategy=framework` para montar corretamente o `redirect_uri` atrás do proxy do Angular CLI e de reverse proxies.

### 6.4 Frontend (contrato, implementação fora deste escopo)

- Servidor de desenvolvimento do Angular CLI (`ng serve`, `:4200`) com `proxy.conf.json` encaminhando `/api`, `/bff`, `/oauth2`, `/login`, `/logout` para `http://localhost:8080`, preservando o header `Host` (`changeOrigin: false`).
- O `HttpClient` do Angular já lê o cookie `XSRF-TOKEN` e envia `X-XSRF-TOKEN` em escritas para a mesma origem (nomes idênticos aos padrões do Spring Security). Nenhuma biblioteca OIDC no frontend.
- O Angular usa `GET /bff/me` (em um *guard* de rota) para saber se há usuário logado, navega para `/oauth2/authorization/keycloak` para logar e envia `POST /logout` com o header CSRF para sair.

### 6.5 Hardening incluído

- Actuator do gateway: remover o endpoint `gateway` da exposição; expor apenas `health`, `info`, `prometheus`, `metrics`.
- Logging `DEBUG` de `org.springframework.cloud.gateway` e `org.springframework.web.servlet.function` rebaixado para `INFO` (evita registrar headers de requisição).

## 7. Tratamento de erros

| Situação | Resposta |
|---|---|
| Sem token / token inválido / expirado / `aud` ou `iss` errados (Bearer) | `401` + `WWW-Authenticate: Bearer error="invalid_token"` (padrão Spring) |
| Token válido sem a role/scope exigida | `403` |
| Navegador sem sessão em `/api/**` ou `/bff/me` | `401` |
| Navegador com sessão, sem header CSRF em escrita | `403` |
| Refresh token expirado/revogado no TokenRelay | sessão invalidada, `401` (o Angular redireciona para login) |
| order-service não consegue token de serviço (Keycloak fora) | `OAuth2AuthorizationException` tratada como `503` no `GlobalExceptionHandler` do order |
| inventory responde `401`/`403` ao `/reserve` (token de serviço rejeitado) | **não** é mascarado como estoque insuficiente: só `409` vira `InsufficientStockException`; demais erros seguem para o handler de Feign (`502`) |
| Keycloak fora no startup | Resource Servers sobem normalmente (JWKS buscado sob demanda e em cache); validações falham com `401` até ele voltar |

## 8. Testes

Hoje cada serviço só tem o teste de contexto. Este trabalho adiciona:

1. **Slice tests de autorização** (`@WebMvcTest` + `spring-security-test`, `jwt()` com autoridades) em product, inventory e order, cobrindo a matriz anônimo / `CUSTOMER` / `ADMIN` / `SCOPE_inventory:reserve` para cada rota da seção 5.
2. **Conversor de autoridades:** teste unitário (roles + scopes, claim ausente, claim malformado).
3. **order-service:** teste unitário da regra de dono do pedido usando `sub`; teste com WireMock garantindo que a chamada Feign ao inventory leva `Authorization: Bearer` obtido via client_credentials.
4. **api-gateway:**
   - Bearer → sem criação de sessão.
   - Sem sessão em `/api/order` → `401` (não 302).
   - Escrita com sessão sem CSRF → `403`; com CSRF → repassada.
   - `/bff/me` com e sem `oidcLogin()`.
   - `/api/inventory/reserve` negado no gateway.
   - Header `Cookie` não repassado aos serviços.
5. **Realm como código:** teste de integração com Testcontainers (`GenericContainer` com a imagem oficial do Keycloak) que importa `realm-ecommerce.json` e verifica: client_credentials do `order-service` gera token com `scope` contendo `inventory:reserve` e `aud` contendo `ecommerce-api`; Password Grant recusado em todos os clients; usuário admin tem `ADMIN`.
6. **Verificação manual ponta a ponta** (checklist no plano): login pelo navegador via gateway, criação de pedido, reserva de estoque, logout, restart do gateway mantendo a sessão, Bruno com PKCE.

## 9. Observabilidade

- Prometheus passa a coletar métricas do Keycloak (`keycloak:9000/metrics`).
- Logs de segurança: falhas de autenticação/autorização em `WARN` com `traceId`, sem tokens nem headers `Authorization`/`Cookie`.

## 10. Documentação e ferramentas

- `.env.example`: remove `JWT_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`; adiciona `KEYCLOAK_ADMIN_PASSWORD` (console master), `KEYCLOAK_DB_PASSWORD`, `KC_BFF_CLIENT_SECRET`, `KC_ORDER_SERVICE_CLIENT_SECRET`, `KC_ADMIN_USER_EMAIL`, `KC_ADMIN_USER_PASSWORD`, `REDIS_PASSWORD`.
- `README.md`: novo fluxo de autenticação, como subir, como rodar pela IDE, como logar pelo Bruno; remove tudo de `/auth/*`.
- `PROJECT_STATUS.md`: atualiza backlog (remove itens do auth-service, adiciona pendências da seção 11).
- `bruno/`: remove `auth-service/` e `AuthDirect.bru`; configura OAuth2 Authorization Code + PKCE (client `ecommerce-dev`) no nível da collection; requests de reserve/release passam a usar client_credentials do `order-service` (apenas ambientes Direct).
- `monitoring/prometheus.yml`: job do Keycloak.

## 11. Fora do escopo

- Implementação do frontend Angular e tema visual da tela de login (Keycloakify).
- `customer-service` (dados de negócio do cliente).
- SMTP, verificação de email, recuperação de senha por email, MFA, login social.
- OIDC Back-Channel Logout (logout feito em outro client derrubar a sessão do BFF).
- Hardening de produção do Keycloak (`start` com TLS, hostname real, banco dedicado) e Redis em alta disponibilidade.
- Alinhar versões de Spring Boot (hoje variam de 4.0.1 a 4.0.6) e Spring Cloud entre os serviços: recomendado como tarefa separada.

## 12. Riscos

| Risco | Mitigação |
|---|---|
| `iss` diferente entre Docker e IDE | Seção 4.4; teste manual nos dois modos |
| Placeholders do realm não substituídos | Teste de integração do realm (seção 8, item 5) |
| Recriar o volume do Postgres para rodar `init-db.sh` apaga dados locais | Volume atual foi criado em 2026-10-13 sem dados; recriação feita com confirmação explícita |
