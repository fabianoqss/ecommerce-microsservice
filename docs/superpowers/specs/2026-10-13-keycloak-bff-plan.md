# Keycloak + BFF + Resource Servers — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir o auth-service (JWT HMAC caseiro) por Keycloak, transformar gateway, product, inventory e order em Resource Servers, autenticar order → inventory com Client Credentials e fazer do api-gateway um BFF com sessão no Redis.

**Architecture:** Keycloak 26.4.7 emite tokens RS256 (realm `ecommerce` versionado em `keycloak/realm-ecommerce.json`). Cada serviço valida tokens sozinho via `spring-boot-starter-security-oauth2-resource-server`. O api-gateway tem duas cadeias de segurança: Bearer (Resource Server, stateless) e sessão (BFF com `oauth2Login`, Spring Session Redis, CSRF para SPA e um filtro próprio que injeta o access token da sessão nas chamadas `/api/**`). O auth-service é removido no fim, depois que tudo funciona sem ele.

**Tech Stack:** Java 21, Spring Boot 4.0.x, Spring Security 7, Spring Cloud 2025.1 (Gateway Server WebMvc, OpenFeign), Spring Session Data Redis 4, Keycloak 26.4.7, Redis 7.4, PostgreSQL 16, Testcontainers 2.0.x, WireMock 3.13.2, Bruno.

**Spec:** `docs/superpowers/specs/2026-10-13-keycloak-bff-design.md`

## Global Constraints

- Imagem do Keycloak: `quay.io/keycloak/keycloak:26.4.7`. Imagem do Redis: `redis:7.4.11-alpine3.21`.
- Realm: `ecommerce`. Issuer: `http://localhost:8180/realms/ecommerce` (sempre, em Docker e na IDE).
- Variáveis: `KEYCLOAK_ISSUER_URI` (padrão `http://localhost:8180/realms/ecommerce`), `KEYCLOAK_INTERNAL_URL` (padrão `http://localhost:8180`; no compose `http://keycloak:8080`).
- Clients: `ecommerce-bff` (confidencial, PKCE S256), `ecommerce-dev` (público, PKCE S256), `order-service` (confidencial, client_credentials). Password Grant desligado em todos.
- Roles: `ADMIN`, `CUSTOMER` (default). Scope de serviço: `inventory:reserve`. Audience obrigatória: `ecommerce-api`.
- Autoridades no Spring: `ROLE_<realm role>` e `SCOPE_<scope>`.
- Nenhum segredo no Git: segredos só no `.env` (ignorado) e em `${VAR}` no realm.
- Portas novas publicadas só em `127.0.0.1`: Keycloak `8180`, Redis `6379`.
- Starters do Spring Boot 4: `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-security-oauth2-client`, `spring-boot-starter-session-data-redis`, `spring-boot-starter-security-test` (todos confirmados no Maven Central para 4.0.2).
- Os testes `*ApplicationTests.contextLoads` existentes dependem da infraestrutura rodando; neste plano rode sempre os testes **pelo nome** (`-Dtest=...`).
- Mensagens de commit em português, no padrão Conventional Commits já usado no repositório, terminando com a linha `Co-Authored-By` da sessão.
- Nunca mencionar "TCC" em código, docs ou commits.

## Requisitos de segurança (critério de conclusão)

O plano só está concluído quando **todos** os itens abaixo estiverem verificados pela prova indicada. Cada prova é um teste automatizado ou um passo da Task 12.

| # | Requisito | Prova |
|---|---|---|
| S1 | Nenhum serviço emite nem valida JWT com código próprio; não existe segredo compartilhado (`JWT_SECRET`) | Task 5 Step 7, Task 9 Step 2 (`grep` vazio) |
| S2 | Tokens assinados com chave assimétrica (RS256) e validados via JWKS, com `iss` e `aud=ecommerce-api` | Task 2 (`aud`), configuração `issuer-uri` + `audiences` nas Tasks 3–7 |
| S3 | Senha do usuário nunca passa pelo backend nem pelo frontend; Password Grant desligado em todos os clients | Task 2 `passwordGrantIsRejectedForEveryClient` |
| S4 | Login de usuário só com Authorization Code + PKCE S256 | Task 2 `userClientsRequirePkceS256`; Task 8 `browserNavigationWithoutSessionStartsLogin` |
| S5 | product, inventory e order rejeitam requisição sem token válido mesmo acessados sem o gateway | Tasks 3–5 (testes `anonymous*`); Task 12 Step 2 |
| S6 | Autorização mínima: escrita de catálogo/estoque só `ADMIN`; pedidos visíveis só ao dono (`sub`) ou `ADMIN` | Tasks 3–5 (`customerCannotWrite`, `onlyAdminManagesItems`, `customerCannotReadOrderOfAnotherSubject`) |
| S7 | `/reserve` e `/release` só com o token de serviço do order-service; negados na borda | Task 4 `customerAndAdminCannotReserve`; Task 7 `reserveAndReleaseAreDeniedAtTheEdge`; Task 12 Step 3 |
| S8 | Navegador nunca recebe access/refresh token; só cookie `SESSION` `HttpOnly`, `SameSite=Lax` | Task 12 Step 4.4 |
| S9 | Proteção CSRF em toda escrita feita com sessão | Task 8 `writeWithoutCsrfIsForbidden` |
| S10 | Cookie de sessão não é repassado aos microsserviços | Task 8 `sessionRequestIsRelayedWithBearerAndWithoutCookie` |
| S11 | Logout encerra a sessão no gateway **e** no Keycloak | Task 8 `logoutRedirectsToKeycloakEndSession`; Task 12 Step 4.6 |
| S12 | Refresh token inválido derruba a sessão (`401`), sem chamada anônima ao serviço | Task 8 `relayWithoutAuthorizedClientReturns401AndInvalidatesSession` |
| S13 | Proteção contra força bruta e política de senha (mínimo 12) ativas | Realm (Task 1); verificação manual na tela de registro |
| S14 | Access token curto (5 min) e rotação de refresh token | Realm (Task 1) |
| S15 | Nenhum segredo versionado; portas novas só em `127.0.0.1` | Task 1 Step 1 (`grep`); compose da Task 1 |
| S16 | Actuator: só `health`, `info` e `prometheus` públicos; endpoint `gateway` fora | Tasks 3, 7 (`actuatorHealth...`, `gatewayActuatorEndpointIsNotExposed`) |
| S17 | Logs sem tokens nem headers `Authorization`/`Cookie` (gateway sem `DEBUG`) | Task 7 Step 2 |
| S18 | Falha do Keycloak não vira `500` nem erro enganoso | Task 6 `keycloakDownReturns503`, `reserveForbiddenIsNotReportedAsInsufficientStock` |

## Review Focus

1. **Restart do gateway com usuário logado** → a sessão continua e as chamadas `/api/**` continuam recebendo o Bearer (tokens ficam na sessão Redis, não em memória). Teste: Task 8, `relayUsesAuthorizedClientFromSession` + checklist da Task 12.
2. **Token de serviço rejeitado pelo inventory (401/403)** → o pedido falha com `502`, **não** com "estoque insuficiente" (`409`). Teste: Task 6, `reserveForbiddenIsNotReportedAsInsufficientStock`.
3. **Keycloak fora do ar durante a criação de pedido** → `503` com ProblemDetail, nunca `500`. Teste: Task 6, `keycloakDownReturns503`.
4. **Bearer inválido numa rota pública (`GET /api/product`)** → `401` (o Resource Server rejeita token inválido mesmo em rota pública; o cliente deve mandar a requisição sem header). Teste: Task 3, `invalidBearerOnPublicRouteIs401`.
5. **Refresh token expirado/revogado com sessão ainda viva** → `401` em `/api/**` e sessão invalidada (o Angular manda para o login), nunca `500` nem chamada sem token ao serviço. Teste: Task 8, `relayWithoutAuthorizedClientReturns401AndInvalidatesSession`.

---

## Mapa de arquivos

| Arquivo | Responsabilidade | Task |
|---|---|---|
| `keycloak/realm-ecommerce.json` | Realm versionado (clients, roles, scopes, usuário admin) | 1 |
| `keycloak/scripts/pkce-login.sh` | Login PKCE via linha de comando para smoke tests | 1 |
| `init-db.sh` (substitui `init-db.sql`) | Cria databases e o usuário `keycloak` | 1 |
| `docker-compose.yml` | Keycloak, Redis; depois wiring dos serviços e remoção do auth-service | 1, 9 |
| `.env.example` | Novas variáveis | 1, 9 |
| `api-gateway/src/test/java/.../KeycloakRealmIntegrationTest.java` | Valida o realm num Keycloak real | 2 |
| `product-service/.../config/SecurityConfig.java`, `KeycloakJwtAuthenticationConverter.java` | Resource Server do product | 3 |
| `inventory-service/.../config/SecurityConfig.java`, `KeycloakJwtAuthenticationConverter.java` | Resource Server do inventory | 4 |
| `order-service/.../infra/security/SecurityConfig.java`, `KeycloakJwtAuthenticationConverter.java` | Resource Server do order (remove filtros manuais) | 5 |
| `order-service/.../infra/security/OAuth2ClientConfig.java` | Client Credentials para o Feign | 6 |
| `api-gateway/.../infra/security/*` | Cadeias Bearer e sessão, relay, logout, `/bff/me` | 7, 8 |
| `monitoring/prometheus.yml` | Job do Keycloak, remove nada do auth (não existia) | 9 |
| `bruno/**` | Login via Keycloak, client_credentials no reserve/release | 10 |
| `README.md`, `PROJECT_STATUS.md` | Documentação | 11 |

---

### Task 0: Branch de trabalho

**Files:** nenhum arquivo de código.

- [ ] **Step 1: Criar o branch a partir da `main`**

```bash
cd ~/projetos/ecommerce-microsservice
git switch -c feat/keycloak-bff
```

- [ ] **Step 2: Commitar spec e plano**

```bash
git add docs/superpowers/specs/2026-10-13-keycloak-bff-design.md docs/superpowers/specs/2026-10-13-keycloak-bff-plan.md
git commit -m "docs: spec e plano da migração para Keycloak, BFF e Resource Servers"
```

---

### Task 1: Infraestrutura — Keycloak, Redis e realm versionado

**Files:**
- Create: `keycloak/realm-ecommerce.json`
- Create: `keycloak/scripts/pkce-login.sh`
- Create: `init-db.sh`
- Delete: `init-db.sql`
- Modify: `docker-compose.yml` (serviço `postgres`, novos `keycloak` e `redis`, volume `redis-data`)
- Modify: `.env.example`, `.env` (local, não versionado)

**Interfaces:**
- Produces: Keycloak em `http://localhost:8180` com realm `ecommerce`; Redis em `localhost:6379` com senha `REDIS_PASSWORD`; segredos `KC_BFF_CLIENT_SECRET`, `KC_ORDER_SERVICE_CLIENT_SECRET` usados nas Tasks 6, 8 e 10.

- [ ] **Step 1: Criar o realm versionado**

O arquivo foi gerado e validado contra um Keycloak 26.4.7 real em 2026-10-13 (client_credentials com `aud=ecommerce-api` e `scope=inventory:reserve`; Password Grant recusado; login PKCE nos dois clients; ID token com `realm_access.roles`; usuário novo recebe `CUSTOMER`). A cópia validada está em `/tmp/claude-1000/-home-fabiano-projetos-ecommerce-microsservice/0ed1b6b4-1ac0-4083-a90f-13bbdd72efb3/scratchpad/kc/realm-ecommerce.json`. Copie-a:

```bash
mkdir -p keycloak/scripts
cp /tmp/claude-1000/-home-fabiano-projetos-ecommerce-microsservice/0ed1b6b4-1ac0-4083-a90f-13bbdd72efb3/scratchpad/kc/realm-ecommerce.json keycloak/realm-ecommerce.json
```

Se a cópia não existir mais, regenere: suba um Keycloak 26.4.7 descartável, crie um realm vazio `defaults` pela Admin API, salve `GET /admin/realms/defaults/client-scopes` em `default-scopes.json` e rode o gerador abaixo (`python3 -I gen.py default-scopes.json keycloak/realm-ecommerce.json`):

```python
import json, sys
defaults = {s['name']: s for s in json.load(open(sys.argv[1]))}
def clean(o):
    if isinstance(o, dict): return {k: clean(v) for k, v in o.items() if k != 'id'}
    if isinstance(o, list): return [clean(v) for v in o]
    return o
keep = ['basic', 'profile', 'email', 'roles', 'web-origins', 'acr', 'service_account', 'offline_access']
scopes = [clean(defaults[n]) for n in keep]
for s in scopes:
    if s['name'] == 'roles':
        for m in s['protocolMappers']:
            if m['protocolMapper'] == 'oidc-usermodel-realm-role-mapper':
                m['config']['id.token.claim'] = 'true'
                m['config']['userinfo.token.claim'] = 'true'
scopes.append({"name": "inventory:reserve", "description": "Reservar e liberar estoque (somente order-service)",
    "protocol": "openid-connect",
    "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"}})
scopes.append({"name": "ecommerce-api", "description": "Audience comum das APIs do e-commerce",
    "protocol": "openid-connect",
    "attributes": {"include.in.token.scope": "false", "display.on.consent.screen": "false"},
    "protocolMappers": [{"name": "audience ecommerce-api", "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper", "consentRequired": False,
        "config": {"included.custom.audience": "ecommerce-api", "access.token.claim": "true",
                   "id.token.claim": "false", "introspection.token.claim": "true"}}]})
realm = {
  "realm": "ecommerce", "enabled": True, "sslRequired": "external",
  "registrationAllowed": True, "registrationEmailAsUsername": True, "loginWithEmailAllowed": True,
  "duplicateEmailsAllowed": False, "verifyEmail": False, "resetPasswordAllowed": False,
  "bruteForceProtected": True, "permanentLockout": False, "failureFactor": 5,
  "passwordPolicy": "length(12) and notUsername and notEmail",
  "accessTokenLifespan": 300, "ssoSessionIdleTimeout": 1800, "ssoSessionMaxLifespan": 36000,
  "revokeRefreshToken": True, "refreshTokenMaxReuse": 0,
  "roles": {"realm": [
      {"name": "ADMIN", "description": "Administrador da loja"},
      {"name": "CUSTOMER", "description": "Cliente da loja"},
      {"name": "offline_access"}, {"name": "uma_authorization"},
      {"name": "default-roles-ecommerce", "composite": True,
       "composites": {"realm": ["CUSTOMER", "offline_access", "uma_authorization"]}}]},
  "defaultRole": {"name": "default-roles-ecommerce"},
  "clientScopes": scopes,
  "defaultDefaultClientScopes": ["basic", "profile", "email", "roles", "web-origins", "acr", "ecommerce-api"],
  "defaultOptionalClientScopes": ["offline_access"],
  "clients": [
    {"clientId": "ecommerce-bff", "name": "E-commerce BFF (api-gateway)", "enabled": True,
     "protocol": "openid-connect", "publicClient": False, "clientAuthenticatorType": "client-secret",
     "secret": "${KC_BFF_CLIENT_SECRET}",
     "standardFlowEnabled": True, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
     "serviceAccountsEnabled": False, "frontchannelLogout": False,
     "redirectUris": ["http://localhost:4200/login/oauth2/code/keycloak", "http://localhost:8080/login/oauth2/code/keycloak"],
     "webOrigins": [],
     "attributes": {"pkce.code.challenge.method": "S256",
                    "post.logout.redirect.uris": "http://localhost:4200/*##http://localhost:8080/*"},
     "defaultClientScopes": ["basic", "profile", "email", "roles", "web-origins", "acr", "ecommerce-api"],
     "optionalClientScopes": ["offline_access"]},
    {"clientId": "ecommerce-dev", "name": "Bruno (somente desenvolvimento)", "enabled": True,
     "protocol": "openid-connect", "publicClient": True,
     "standardFlowEnabled": True, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
     "serviceAccountsEnabled": False,
     "redirectUris": ["https://oauth.usebruno.com/callback"], "webOrigins": [],
     "attributes": {"pkce.code.challenge.method": "S256"},
     "defaultClientScopes": ["basic", "profile", "email", "roles", "acr", "ecommerce-api"],
     "optionalClientScopes": []},
    {"clientId": "order-service", "name": "order-service (service account)", "enabled": True,
     "protocol": "openid-connect", "publicClient": False, "clientAuthenticatorType": "client-secret",
     "secret": "${KC_ORDER_SERVICE_CLIENT_SECRET}",
     "standardFlowEnabled": False, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
     "serviceAccountsEnabled": True,
     "defaultClientScopes": ["basic", "service_account", "ecommerce-api", "inventory:reserve"],
     "optionalClientScopes": []}],
  "users": [
    {"username": "${KC_ADMIN_USER_EMAIL}", "email": "${KC_ADMIN_USER_EMAIL}", "emailVerified": True,
     "enabled": True, "firstName": "Admin", "lastName": "E-commerce",
     "credentials": [{"type": "password", "value": "${KC_ADMIN_USER_PASSWORD}", "temporary": False}],
     "realmRoles": ["default-roles-ecommerce", "ADMIN"]}]
}
json.dump(realm, open(sys.argv[2], 'w'), indent=2, ensure_ascii=False)
```

Confira que não há segredo literal no arquivo:

```bash
grep -nE '"(secret|value)"' keycloak/realm-ecommerce.json
```

Expected: só linhas com `${KC_BFF_CLIENT_SECRET}`, `${KC_ORDER_SERVICE_CLIENT_SECRET}` e `${KC_ADMIN_USER_PASSWORD}`.

- [ ] **Step 2: Criar o script de login PKCE (smoke test manual)**

`keycloak/scripts/pkce-login.sh`:

```bash
#!/usr/bin/env bash
# Faz login Authorization Code + PKCE no realm "ecommerce" sem navegador e imprime as claims dos tokens.
# Uso: pkce-login.sh BASE_URL CLIENT_ID CLIENT_SECRET|- USUARIO SENHA REDIRECT_URI
# Ex.: keycloak/scripts/pkce-login.sh http://localhost:8180 ecommerce-dev - admin@ecommerce.local 'senha' https://oauth.usebruno.com/callback
set -euo pipefail
BASE=$1 CLIENT=$2 SECRET=$3 USER=$4 PASS=$5 REDIRECT=$6
JAR=$(mktemp); trap 'rm -f "$JAR"' EXIT
VERIFIER=$(openssl rand -base64 48 | tr -d '=+/\n' | cut -c1-64)
CHALLENGE=$(printf %s "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 | tr '+/' '-_' | tr -d '=\n')
PAGE=$(curl -s -c "$JAR" -b "$JAR" -G "$BASE/realms/ecommerce/protocol/openid-connect/auth" \
  --data-urlencode "client_id=$CLIENT" --data-urlencode response_type=code \
  --data-urlencode "scope=openid profile email" --data-urlencode "redirect_uri=$REDIRECT" \
  --data-urlencode "code_challenge=$CHALLENGE" --data-urlencode code_challenge_method=S256 \
  --data-urlencode state=smoke)
ACTION=$(printf %s "$PAGE" | grep -o 'action="[^"]*"' | head -1 | sed 's/action="//;s/"$//;s/&amp;/\&/g')
LOCATION=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{redirect_url}' \
  --data-urlencode "username=$USER" --data-urlencode "password=$PASS" "$ACTION")
CODE=$(python3 -I -c "import sys,urllib.parse as u;print(u.parse_qs(u.urlparse(sys.argv[1]).query)['code'][0])" "$LOCATION")
ARGS=(-d grant_type=authorization_code --data-urlencode "code=$CODE" --data-urlencode "redirect_uri=$REDIRECT" \
  -d "client_id=$CLIENT" -d "code_verifier=$VERIFIER")
[ "$SECRET" != "-" ] && ARGS+=(-d "client_secret=$SECRET")
curl -s "${ARGS[@]}" "$BASE/realms/ecommerce/protocol/openid-connect/token" | python3 -I -c "
import sys, json, base64
t = json.load(sys.stdin)
def claims(x):
    p = x.split('.')[1]; p += '=' * (-len(p) % 4); return json.loads(base64.urlsafe_b64decode(p))
keys = ['iss', 'aud', 'sub', 'azp', 'scope', 'email', 'name', 'realm_access']
print('ACCESS', {k: claims(t['access_token']).get(k) for k in keys})
print('ID    ', {k: claims(t['id_token']).get(k) for k in keys})
print('ACCESS_TOKEN', t['access_token'])"
```

```bash
chmod +x keycloak/scripts/pkce-login.sh
```

- [ ] **Step 3: Trocar `init-db.sql` por `init-db.sh`**

O SQL puro não lê variáveis de ambiente; o `.sh` lê `KEYCLOAK_DB_PASSWORD`. O database `auth_service` continua até a Task 9 (o auth-service ainda roda até lá).

`init-db.sh`:

```bash
#!/bin/bash
# Executado pelo entrypoint do Postgres apenas na criação do volume.
set -euo pipefail
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD não definido}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v kc_password="$KEYCLOAK_DB_PASSWORD" <<-'EOSQL'
	CREATE DATABASE "order-service";
	CREATE DATABASE inventory_service;
	CREATE DATABASE auth_service;
	CREATE USER keycloak WITH PASSWORD :'kc_password';
	CREATE DATABASE keycloak OWNER keycloak;
EOSQL
```

```bash
chmod +x init-db.sh
git rm init-db.sql
```

- [ ] **Step 4: Atualizar o `docker-compose.yml`**

No serviço `postgres`, troque o volume do script e passe a senha do Keycloak:

```yaml
  postgres:
    image: postgres:16
    container_name: postgres
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${DB_PASSWORD}
      KEYCLOAK_DB_PASSWORD: ${KEYCLOAK_DB_PASSWORD}
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./init-db.sh:/docker-entrypoint-initdb.d/init-db.sh:ro
```

(mantenha `healthcheck` e `restart` como estão.)

Logo depois de `rabbitmq`, ainda na seção de infraestrutura, adicione:

```yaml
  redis:
    image: redis:7.4.11-alpine3.21
    container_name: redis
    command: ["sh", "-c", "exec redis-server --requirepass \"$$REDIS_PASSWORD\" --appendonly yes"]
    environment:
      REDIS_PASSWORD: ${REDIS_PASSWORD}
    ports:
      - "127.0.0.1:6379:6379"
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$REDIS_PASSWORD\" --no-auth-warning ping | grep -q PONG"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  keycloak:
    image: quay.io/keycloak/keycloak:26.4.7
    container_name: keycloak
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD}
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: ${KEYCLOAK_DB_PASSWORD}
      KC_HOSTNAME: http://localhost:8180
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"
      KC_HEALTH_ENABLED: "true"
      KC_METRICS_ENABLED: "true"
      KC_BFF_CLIENT_SECRET: ${KC_BFF_CLIENT_SECRET}
      KC_ORDER_SERVICE_CLIENT_SECRET: ${KC_ORDER_SERVICE_CLIENT_SECRET}
      KC_ADMIN_USER_EMAIL: ${KC_ADMIN_USER_EMAIL}
      KC_ADMIN_USER_PASSWORD: ${KC_ADMIN_USER_PASSWORD}
    ports:
      - "127.0.0.1:8180:8080"
    volumes:
      - ./keycloak/realm-ecommerce.json:/opt/keycloak/data/import/realm-ecommerce.json:ro
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      # A imagem não tem curl; o health fica na porta de gerenciamento 9000.
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
      interval: 15s
      timeout: 5s
      retries: 20
      start_period: 30s
    restart: unless-stopped
```

No bloco `volumes:` do fim do arquivo, adicione `redis-data:`.

- [ ] **Step 5: Atualizar `.env.example` e o `.env` local**

Acrescente ao `.env.example` (mantenha as linhas existentes até a Task 9):

```dotenv
# Keycloak
KEYCLOAK_ADMIN_PASSWORD=troque-senha-do-console-admin
KEYCLOAK_DB_PASSWORD=troque-senha-do-banco-keycloak
KC_BFF_CLIENT_SECRET=troque-segredo-do-client-ecommerce-bff
KC_ORDER_SERVICE_CLIENT_SECRET=troque-segredo-do-client-order-service
KC_ADMIN_USER_EMAIL=admin@ecommerce.local
KC_ADMIN_USER_PASSWORD=troque-senha-com-12-caracteres-ou-mais
# Redis (sessões do api-gateway)
REDIS_PASSWORD=troque-senha-do-redis
```

Gere os valores reais no `.env` local (arquivo já ignorado pelo Git):

```bash
for v in KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASSWORD KC_BFF_CLIENT_SECRET KC_ORDER_SERVICE_CLIENT_SECRET REDIS_PASSWORD; do
  grep -q "^$v=" .env || echo "$v=$(openssl rand -base64 32 | tr -d '/+=')" >> .env
done
grep -q '^KC_ADMIN_USER_EMAIL=' .env || echo 'KC_ADMIN_USER_EMAIL=admin@ecommerce.local' >> .env
grep -q '^KC_ADMIN_USER_PASSWORD=' .env || echo "KC_ADMIN_USER_PASSWORD=$(openssl rand -base64 18 | tr -d '/+=')Aa1!" >> .env
```

- [ ] **Step 6: Recriar o volume do Postgres (DESTRUTIVO — pedir confirmação ao usuário antes)**

O `init-db.sh` só roda num volume novo. Confirme com o usuário que os dados locais podem ser apagados e então:

```bash
docker compose stop postgres
docker compose rm -f postgres
docker volume rm ecommerce-microsservice_postgres-data
docker compose up -d postgres redis keycloak
```

- [ ] **Step 7: Verificar a infraestrutura**

```bash
docker compose ps postgres redis keycloak
docker exec postgres psql -U postgres -tAc "select datname from pg_database where not datistemplate order by 1"
docker exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping'
curl -s http://localhost:8180/realms/ecommerce/.well-known/openid-configuration | python3 -I -c "import sys,json;d=json.load(sys.stdin);print(d['issuer'])"
```

Expected: três containers `healthy`; databases `auth_service`, `inventory_service`, `keycloak`, `order-service`, `postgres`; `PONG`; issuer `http://localhost:8180/realms/ecommerce`.

- [ ] **Step 8: Smoke test do realm**

```bash
set -a; . ./.env; set +a
echo "== client_credentials (deve ter aud ecommerce-api e scope inventory:reserve)"
curl -s -d grant_type=client_credentials -d client_id=order-service -d "client_secret=$KC_ORDER_SERVICE_CLIENT_SECRET" \
  http://localhost:8180/realms/ecommerce/protocol/openid-connect/token \
  | python3 -I -c "import sys,json,base64;t=json.load(sys.stdin)['access_token'].split('.')[1];t+='='*(-len(t)%4);c=json.loads(base64.urlsafe_b64decode(t));print(c['iss'],c['aud'],c['scope'])"
echo "== password grant (deve falhar com unauthorized_client)"
curl -s -d grant_type=password -d client_id=ecommerce-dev -d "username=$KC_ADMIN_USER_EMAIL" -d "password=$KC_ADMIN_USER_PASSWORD" \
  http://localhost:8180/realms/ecommerce/protocol/openid-connect/token; echo
echo "== login PKCE do admin"
keycloak/scripts/pkce-login.sh http://localhost:8180 ecommerce-dev - "$KC_ADMIN_USER_EMAIL" "$KC_ADMIN_USER_PASSWORD" https://oauth.usebruno.com/callback | head -2
```

Expected: `http://localhost:8180/realms/ecommerce ecommerce-api inventory:reserve`; `{"error":"unauthorized_client",...}`; ACCESS com `realm_access.roles` contendo `ADMIN`.

- [ ] **Step 9: Commit**

```bash
git add keycloak/ init-db.sh docker-compose.yml .env.example
git commit -m "feat(infra): adiciona Keycloak com realm versionado e Redis"
```

---

### Task 2: Teste de integração do realm (realm como código)

**Files:**
- Modify: `api-gateway/pom.xml` (dependências de teste)
- Create: `api-gateway/src/test/java/com/example/ecommerce/api_gateway/KeycloakRealmIntegrationTest.java`

**Interfaces:**
- Consumes: `keycloak/realm-ecommerce.json` (Task 1).

- [ ] **Step 1: Adicionar dependências de teste no `api-gateway/pom.xml`**

Dentro de `<dependencies>`:

```xml
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
```

(versão gerenciada pelo Spring Boot 4: Testcontainers 2.0.x.)

- [ ] **Step 2: Escrever o teste**

`KeycloakRealmIntegrationTest.java`:

```java
package com.example.ecommerce.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sobe o Keycloak real com keycloak/realm-ecommerce.json e verifica as garantias de segurança do realm.
 * Requer Docker.
 */
@Testcontainers
class KeycloakRealmIntegrationTest {

    private static final String ORDER_SECRET = "order-secret-test";
    private static final String ADMIN_EMAIL = "admin@ecommerce.test";

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.4.7")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("KC_BFF_CLIENT_SECRET", "bff-secret-test")
            .withEnv("KC_ORDER_SERVICE_CLIENT_SECRET", ORDER_SECRET)
            .withEnv("KC_ADMIN_USER_EMAIL", ADMIN_EMAIL)
            .withEnv("KC_ADMIN_USER_PASSWORD", "AdminPassword-123")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "keycloak", "realm-ecommerce.json").toAbsolutePath()),
                    "/opt/keycloak/data/import/realm-ecommerce.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonParser json = JsonParserFactory.getJsonParser();

    @Test
    void orderServiceClientCredentialsTokenHasReserveScopeAndAudience() throws Exception {
        Map<String, Object> token = postForm("/realms/ecommerce/protocol/openid-connect/token", Map.of(
                "grant_type", "client_credentials",
                "client_id", "order-service",
                "client_secret", ORDER_SECRET));

        Map<String, Object> claims = claims((String) token.get("access_token"));
        assertThat(((String) claims.get("scope")).split(" ")).contains("inventory:reserve");
        assertThat(audiences(claims)).contains("ecommerce-api");
    }

    @Test
    void passwordGrantIsRejectedForEveryClient() throws Exception {
        for (String client : List.of("ecommerce-bff", "ecommerce-dev", "order-service")) {
            HttpResponse<String> response = postFormRaw("/realms/ecommerce/protocol/openid-connect/token", Map.of(
                    "grant_type", "password",
                    "client_id", client,
                    "username", ADMIN_EMAIL,
                    "password", "AdminPassword-123"));
            assertThat(response.statusCode()).as(client).isIn(400, 401);
            assertThat(response.body()).as(client).contains("unauthorized_client");
        }
    }

    @Test
    void userClientsRequirePkceS256() throws Exception {
        String adminToken = adminToken();
        for (String client : List.of("ecommerce-bff", "ecommerce-dev")) {
            Map<String, Object> rep = getAdmin("/admin/realms/ecommerce/clients?clientId=" + client, adminToken).getFirst();
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) rep.get("attributes");
            assertThat(attributes.get("pkce.code.challenge.method")).as(client).isEqualTo("S256");
            assertThat(rep.get("directAccessGrantsEnabled")).as(client).isEqualTo(false);
        }
    }

    @Test
    void initialAdminUserHasAdminRoleAndNewUsersGetCustomer() throws Exception {
        String adminToken = adminToken();
        Map<String, Object> user = getAdmin("/admin/realms/ecommerce/users?exact=true&email=" + ADMIN_EMAIL, adminToken).getFirst();
        List<Map<String, Object>> roles = getAdmin(
                "/admin/realms/ecommerce/users/" + user.get("id") + "/role-mappings/realm/composite", adminToken);
        assertThat(roles).extracting(r -> r.get("name")).contains("ADMIN", "CUSTOMER");

        Map<String, Object> defaultRole = getAdmin("/admin/realms/ecommerce/roles/default-roles-ecommerce/composites", adminToken)
                .stream().filter(r -> "CUSTOMER".equals(r.get("name"))).findFirst().orElse(null);
        assertThat(defaultRole).isNotNull();
    }

    private String baseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    private String adminToken() throws Exception {
        return (String) postForm("/realms/master/protocol/openid-connect/token", Map.of(
                "grant_type", "password", "client_id", "admin-cli", "username", "admin", "password", "admin"))
                .get("access_token");
    }

    private Map<String, Object> postForm(String path, Map<String, String> form) throws Exception {
        HttpResponse<String> response = postFormRaw(path, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.parseMap(response.body());
    }

    private HttpResponse<String> postFormRaw(String path, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAdmin(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return (List<Map<String, Object>>) (List<?>) json.parseList(response.body());
    }

    private Map<String, Object> claims(String jwt) {
        String payload = jwt.split("\\.")[1];
        return json.parseMap(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private List<?> audiences(Map<String, Object> claims) {
        Object aud = claims.get("aud");
        return aud instanceof List<?> list ? list : List.of(aud);
    }
}
```

- [ ] **Step 3: Rodar o teste**

```bash
cd api-gateway && ./mvnw -q -Dtest=KeycloakRealmIntegrationTest test; cd ..
```

Expected: 4 testes PASS. (O realm já foi validado manualmente na Task 1; se algum falhar, o problema está no `realm-ecommerce.json` — corrija o arquivo, não o teste.)

- [ ] **Step 4: Prova de que o teste pega regressão**

Troque temporariamente `"directAccessGrantsEnabled": False` do `ecommerce-dev` para `true` no realm, rode de novo e confirme que `passwordGrantIsRejectedForEveryClient` e `userClientsRequirePkceS256` falham. Desfaça a alteração (`git checkout keycloak/realm-ecommerce.json`).

- [ ] **Step 5: Commit**

```bash
git add api-gateway/pom.xml api-gateway/src/test/java/com/example/ecommerce/api_gateway/KeycloakRealmIntegrationTest.java
git commit -m "test(keycloak): valida o realm versionado em um Keycloak real"
```

---

### Task 3: product-service como Resource Server

**Files:**
- Modify: `product-service/pom.xml`
- Modify: `product-service/src/main/resources/application.yml`
- Create: `product-service/src/main/java/com/ecommerce/product_service/config/KeycloakJwtAuthenticationConverter.java`
- Create: `product-service/src/main/java/com/ecommerce/product_service/config/SecurityConfig.java`
- Test: `product-service/src/test/java/com/ecommerce/product_service/config/KeycloakJwtAuthenticationConverterTest.java`
- Test: `product-service/src/test/java/com/ecommerce/product_service/controllers/ProductSecurityTest.java`

**Interfaces:**
- Produces: `KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken>`; nome da autenticação = `sub`; autoridades `ROLE_*` e `SCOPE_*`.

- [ ] **Step 1: Dependências**

Em `product-service/pom.xml`, dentro de `<dependencies>`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Configuração do Resource Server**

Em `product-service/src/main/resources/application.yml`, dentro do bloco `spring:` existente (abaixo de `mongodb`):

```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          # issuer-uri só valida o claim iss; com jwk-set-uri presente não há discovery na subida.
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ecommerce}
          jwk-set-uri: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}/realms/ecommerce/protocol/openid-connect/certs
          audiences: ecommerce-api
```

- [ ] **Step 3: Teste do conversor (falhando)**

`KeycloakJwtAuthenticationConverterTest.java`:

```java
package com.ecommerce.product_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void mapsRealmRolesAndScopesAndUsesSubjectAsName() {
        Jwt jwt = jwt(Map.of(
                "sub", "user-sub",
                "scope", "openid inventory:reserve",
                "realm_access", Map.of("roles", List.of("ADMIN", "CUSTOMER"))));

        var authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("user-sub");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CUSTOMER", "SCOPE_openid", "SCOPE_inventory:reserve");
    }

    @Test
    void missingRealmAccessYieldsOnlyScopes() {
        var authentication = converter.convert(jwt(Map.of("sub", "svc", "scope", "inventory:reserve")));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_inventory:reserve");
    }

    @Test
    void malformedRealmAccessIsIgnored() {
        var authentication = converter.convert(jwt(Map.of(
                "sub", "x",
                "realm_access", Map.of("roles", "ADMIN"))));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
    }
}
```

Run: `cd product-service && ./mvnw -q -Dtest=KeycloakJwtAuthenticationConverterTest test`
Expected: FAIL de compilação (`KeycloakJwtAuthenticationConverter` não existe).

- [ ] **Step 4: Implementar o conversor**

`KeycloakJwtAuthenticationConverter.java`:

```java
package com.ecommerce.product_service.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converte o JWT do Keycloak em autenticação do Spring: scopes viram SCOPE_*, realm roles
 * (claim realm_access.roles) viram ROLE_*, e o nome da autenticação é o sub (UUID imutável).
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(scopesConverter.convert(jwt));
        authorities.addAll(realmRoles(jwt));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static Set<GrantedAuthority> realmRoles(Jwt jwt) {
        Set<GrantedAuthority> roles = new HashSet<>();
        if (jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> names) {
            for (Object name : names) {
                if (name instanceof String role) {
                    roles.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
        }
        return roles;
    }
}
```

Run: `./mvnw -q -Dtest=KeycloakJwtAuthenticationConverterTest test`
Expected: 3 PASS.

- [ ] **Step 5: Teste das regras de autorização (falhando)**

`ProductSecurityTest.java`:

```java
package com.ecommerce.product_service.controllers;

import com.ecommerce.product_service.config.SecurityConfig;
import com.ecommerce.product_service.services.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductSecurityTest {

    private static final String VALID_PRODUCT = """
            {"name":"Teclado","description":"Teclado mecânico RGB","price":349.90,"category":"Periféricos"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** Passou pela segurança: qualquer status que não seja 401/403. */
    private static ResultMatcher allowed() {
        return result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void anonymousCanReadProducts() throws Exception {
        mvc.perform(get("/api/product")).andExpect(allowed());
        mvc.perform(get("/api/product/abc")).andExpect(allowed());
    }

    @Test
    void anonymousCannotWrite() throws Exception {
        mvc.perform(post("/api/product").contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/product/abc")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotWrite() throws Exception {
        var customer = jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        mvc.perform(post("/api/product").with(customer).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/product/abc").with(customer).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/product/abc").with(customer)).andExpect(status().isForbidden());
    }

    @Test
    void adminCanWrite() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(post("/api/product").with(admin).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(allowed());
        mvc.perform(put("/api/product/abc").with(admin).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(allowed());
        mvc.perform(delete("/api/product/abc").with(admin)).andExpect(allowed());
    }

    @Test
    void serviceScopeIsNotAdmin() throws Exception {
        var service = jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));
        mvc.perform(delete("/api/product/abc").with(service)).andExpect(status().isForbidden());
    }

    @Test
    void invalidBearerOnPublicRouteIs401() throws Exception {
        when(jwtDecoder.decode("garbage")).thenThrow(new BadJwtException("invalid"));
        mvc.perform(get("/api/product").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsPublicAndOtherActuatorEndpointsAreDenied() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(allowed());
        mvc.perform(get("/actuator/env").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }
}
```

Run: `./mvnw -q -Dtest=ProductSecurityTest test`
Expected: FAIL de compilação (`SecurityConfig` não existe).

- [ ] **Step 6: Implementar o `SecurityConfig`**

`SecurityConfig.java`:

```java
package com.ecommerce.product_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/product", "/api/product/**").permitAll()
                        .requestMatchers("/api/product", "/api/product/**").hasRole("ADMIN")
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter())))
                .build();
    }
}
```

Run: `./mvnw -q -Dtest='ProductSecurityTest,KeycloakJwtAuthenticationConverterTest' test`
Expected: todos PASS.

- [ ] **Step 7: Commit**

```bash
cd ..
git add product-service/pom.xml product-service/src/main/resources/application.yml \
  product-service/src/main/java/com/ecommerce/product_service/config/ \
  product-service/src/test/java/com/ecommerce/product_service/
git commit -m "feat(product): valida tokens do Keycloak como Resource Server"
```

---

### Task 4: inventory-service como Resource Server

**Files:**
- Modify: `inventory-service/pom.xml`
- Modify: `inventory-service/src/main/resources/application.yml`
- Create: `inventory-service/src/main/java/com/example/ecommerce/inventory_service/config/KeycloakJwtAuthenticationConverter.java`
- Create: `inventory-service/src/main/java/com/example/ecommerce/inventory_service/config/SecurityConfig.java`
- Test: `inventory-service/src/test/java/com/example/ecommerce/inventory_service/config/KeycloakJwtAuthenticationConverterTest.java`
- Test: `inventory-service/src/test/java/com/example/ecommerce/inventory_service/controllers/InventorySecurityTest.java`

**Interfaces:**
- Produces: `/api/inventory/reserve` e `/release` exigem a autoridade `SCOPE_inventory:reserve` (consumida pela Task 6).

- [ ] **Step 1: Dependências**

Em `inventory-service/pom.xml`, dentro de `<dependencies>`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Configuração do Resource Server**

Em `inventory-service/src/main/resources/application.yml`, dentro do bloco `spring:` (abaixo de `jpa`):

```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ecommerce}
          jwk-set-uri: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}/realms/ecommerce/protocol/openid-connect/certs
          audiences: ecommerce-api
```

- [ ] **Step 3: Teste do conversor (falhando)**

`KeycloakJwtAuthenticationConverterTest.java` (pacote `com.example.ecommerce.inventory_service.config`):

```java
package com.example.ecommerce.inventory_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void mapsRealmRolesAndScopesAndUsesSubjectAsName() {
        Jwt jwt = jwt(Map.of(
                "sub", "user-sub",
                "scope", "openid inventory:reserve",
                "realm_access", Map.of("roles", List.of("ADMIN", "CUSTOMER"))));

        var authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("user-sub");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CUSTOMER", "SCOPE_openid", "SCOPE_inventory:reserve");
    }

    @Test
    void missingRealmAccessYieldsOnlyScopes() {
        var authentication = converter.convert(jwt(Map.of("sub", "svc", "scope", "inventory:reserve")));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_inventory:reserve");
    }

    @Test
    void malformedRealmAccessIsIgnored() {
        var authentication = converter.convert(jwt(Map.of(
                "sub", "x",
                "realm_access", Map.of("roles", "ADMIN"))));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
    }
}
```

Run: `cd inventory-service && ./mvnw -q -Dtest=KeycloakJwtAuthenticationConverterTest test`
Expected: FAIL de compilação.

- [ ] **Step 4: Implementar o conversor**

`KeycloakJwtAuthenticationConverter.java`:

```java
package com.example.ecommerce.inventory_service.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converte o JWT do Keycloak em autenticação do Spring: scopes viram SCOPE_*, realm roles
 * (claim realm_access.roles) viram ROLE_*, e o nome da autenticação é o sub (UUID imutável).
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(scopesConverter.convert(jwt));
        authorities.addAll(realmRoles(jwt));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static Set<GrantedAuthority> realmRoles(Jwt jwt) {
        Set<GrantedAuthority> roles = new HashSet<>();
        if (jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> names) {
            for (Object name : names) {
                if (name instanceof String role) {
                    roles.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
        }
        return roles;
    }
}
```

Run: `./mvnw -q -Dtest=KeycloakJwtAuthenticationConverterTest test`
Expected: 3 PASS.

- [ ] **Step 5: Teste das regras de autorização (falhando)**

`InventorySecurityTest.java`:

```java
package com.example.ecommerce.inventory_service.controllers;

import com.example.ecommerce.inventory_service.config.SecurityConfig;
import com.example.ecommerce.inventory_service.services.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventorySecurityTest {

    private static final String RESERVE_BODY = """
            {"items":[{"skuCode":"SKU-1","quantity":1}]}
            """;
    private static final String INSERT_BODY = """
            {"skuCode":"SKU-1","quantity":10}
            """;
    private static final String UPDATE_BODY = """
            {"quantity":5}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ResultMatcher allowed() {
        return result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void anonymousCanCheckStock() throws Exception {
        mvc.perform(get("/api/inventory").param("skuCode", "SKU-1")).andExpect(allowed());
    }

    @Test
    void reserveAndReleaseRequireServiceScope() throws Exception {
        var service = jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));
        mvc.perform(post("/api/inventory/reserve").with(service).contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(allowed());
        mvc.perform(post("/api/inventory/release").with(service).contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(allowed());
    }

    @Test
    void customerAndAdminCannotReserve() throws Exception {
        for (String role : new String[]{"ROLE_CUSTOMER", "ROLE_ADMIN"}) {
            var user = jwt().authorities(new SimpleGrantedAuthority(role));
            mvc.perform(post("/api/inventory/reserve").with(user).contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/inventory/release").with(user).contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void anonymousCannotReserve() throws Exception {
        mvc.perform(post("/api/inventory/reserve").contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminManagesItems() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var customer = jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        var service = jwt().authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));

        mvc.perform(post("/api/inventory").with(admin).contentType(MediaType.APPLICATION_JSON).content(INSERT_BODY))
                .andExpect(allowed());
        mvc.perform(put("/api/inventory/1").with(admin).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(allowed());

        mvc.perform(post("/api/inventory").with(customer).contentType(MediaType.APPLICATION_JSON).content(INSERT_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/inventory/1").with(service).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isForbidden());
    }
}
```

Run: `./mvnw -q -Dtest=InventorySecurityTest test`
Expected: FAIL de compilação (`SecurityConfig` não existe).

- [ ] **Step 6: Implementar o `SecurityConfig`**

`SecurityConfig.java`:

```java
package com.example.ecommerce.inventory_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll()
                        // Contrato interno: só o token de serviço do order-service (client_credentials).
                        // Precisa vir antes da regra genérica de POST.
                        .requestMatchers(HttpMethod.POST, "/api/inventory/reserve", "/api/inventory/release")
                            .hasAuthority("SCOPE_inventory:reserve")
                        .requestMatchers(HttpMethod.GET, "/api/inventory", "/api/inventory/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/inventory").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/inventory/**").hasRole("ADMIN")
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter())))
                .build();
    }
}
```

Run: `./mvnw -q -Dtest='InventorySecurityTest,KeycloakJwtAuthenticationConverterTest' test`
Expected: todos PASS.

- [ ] **Step 7: Commit**

```bash
cd ..
git add inventory-service/pom.xml inventory-service/src/main/resources/application.yml \
  inventory-service/src/main/java/com/example/ecommerce/inventory_service/config/ \
  inventory-service/src/test/java/com/example/ecommerce/inventory_service/
git commit -m "feat(inventory): Resource Server com reserve/release restritos ao token de serviço"
```

---

### Task 5: order-service como Resource Server (remove validação manual)

**Files:**
- Modify: `order-service/pom.xml` (remove `java-jwt`, adiciona resource server)
- Modify: `order-service/src/main/resources/application.yml` (remove `jwt.secret`, adiciona resource server)
- Delete: `order-service/src/main/java/com/example/ecommerce/order_service/infra/security/OrderSecurityFilter.java`
- Delete: `order-service/src/main/java/com/example/ecommerce/order_service/infra/security/TokenService.java`
- Create: `order-service/src/main/java/com/example/ecommerce/order_service/infra/security/KeycloakJwtAuthenticationConverter.java`
- Modify: `order-service/src/main/java/com/example/ecommerce/order_service/infra/security/SecurityConfig.java`
- Modify: `order-service/src/main/java/com/example/ecommerce/order_service/services/OrderService.java` (parâmetro `userEmail` → `userId`)
- Test: `order-service/src/test/java/com/example/ecommerce/order_service/infra/security/KeycloakJwtAuthenticationConverterTest.java`
- Test: `order-service/src/test/java/com/example/ecommerce/order_service/controllers/OrderSecurityTest.java`
- Test: `order-service/src/test/java/com/example/ecommerce/order_service/services/OrderServiceOwnershipTest.java`

**Interfaces:**
- Consumes: nada de tasks anteriores.
- Produces: `OrderService.createOrder(OrderRequestDTO request, String userId)`; `Authentication#getName()` = `sub`.

- [ ] **Step 1: Dependências**

Em `order-service/pom.xml`, **remova** o bloco:

```xml
		<dependency>
			<groupId>com.auth0</groupId>
			<artifactId>java-jwt</artifactId>
			<version>4.4.0</version>
		</dependency>
```

e adicione:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
```

(`spring-boot-starter-security-test` já existe no pom.)

- [ ] **Step 2: Configuração**

Em `order-service/src/main/resources/application.yml`, **remova** o bloco final:

```yaml
jwt:
  secret: ${JWT_SECRET}
```

e adicione dentro de `spring:` (abaixo de `rabbitmq`):

```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ecommerce}
          jwk-set-uri: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}/realms/ecommerce/protocol/openid-connect/certs
          audiences: ecommerce-api
```

- [ ] **Step 3: Teste do conversor e implementação**

`KeycloakJwtAuthenticationConverterTest.java` (pacote `com.example.ecommerce.order_service.infra.security`):

```java
package com.example.ecommerce.order_service.infra.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void mapsRealmRolesAndScopesAndUsesSubjectAsName() {
        Jwt jwt = jwt(Map.of(
                "sub", "user-sub",
                "scope", "openid inventory:reserve",
                "realm_access", Map.of("roles", List.of("ADMIN", "CUSTOMER"))));

        var authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("user-sub");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CUSTOMER", "SCOPE_openid", "SCOPE_inventory:reserve");
    }

    @Test
    void missingRealmAccessYieldsOnlyScopes() {
        var authentication = converter.convert(jwt(Map.of("sub", "svc", "scope", "inventory:reserve")));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_inventory:reserve");
    }

    @Test
    void malformedRealmAccessIsIgnored() {
        var authentication = converter.convert(jwt(Map.of(
                "sub", "x",
                "realm_access", Map.of("roles", "ADMIN"))));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
    }
}
```

`KeycloakJwtAuthenticationConverter.java`:

```java
package com.example.ecommerce.order_service.infra.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converte o JWT do Keycloak em autenticação do Spring: scopes viram SCOPE_*, realm roles
 * (claim realm_access.roles) viram ROLE_*, e o nome da autenticação é o sub (UUID imutável).
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(scopesConverter.convert(jwt));
        authorities.addAll(realmRoles(jwt));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static Set<GrantedAuthority> realmRoles(Jwt jwt) {
        Set<GrantedAuthority> roles = new HashSet<>();
        if (jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> names) {
            for (Object name : names) {
                if (name instanceof String role) {
                    roles.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
        }
        return roles;
    }
}
```

- [ ] **Step 4: Testes de segurança do controller e de dono do pedido (falhando)**

`OrderSecurityTest.java`:

```java
package com.example.ecommerce.order_service.controllers;

import com.example.ecommerce.order_service.infra.security.SecurityConfig;
import com.example.ecommerce.order_service.services.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderSecurityTest {

    private static final String ORDER_BODY = """
            {"items":[{"productId":"p1","quantity":1}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/order")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/order").contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createOrderUsesTokenSubjectAsUserId() throws Exception {
        mvc.perform(post("/api/order")
                        .with(jwt().jwt(j -> j.subject("4622f3de-user-sub"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(), eq("4622f3de-user-sub"));
    }
}
```

`OrderServiceOwnershipTest.java`:

```java
package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.entities.Order;
import com.example.ecommerce.order_service.exceptions.OrderAccessDeniedException;
import com.example.ecommerce.order_service.repositories.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceOwnershipTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void customerListsOnlyOwnOrdersBySubject() {
        orderService.findAll(token("user-sub", "ROLE_CUSTOMER"));

        verify(orderRepository).findByUserID("user-sub");
    }

    @Test
    void adminListsAllOrders() {
        orderService.findAll(token("admin-sub", "ROLE_ADMIN"));

        verify(orderRepository).findAll();
    }

    @Test
    void customerCannotReadOrderOfAnotherSubject() {
        Order order = new Order();
        order.setUserID("owner-sub");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.findById(1L, token("intruder-sub", "ROLE_CUSTOMER")))
                .isInstanceOf(OrderAccessDeniedException.class);
    }

    private static JwtAuthenticationToken token(String subject, String authority) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)), subject);
    }
}
```

Run: `cd order-service && ./mvnw -q -Dtest='OrderSecurityTest,OrderServiceOwnershipTest,KeycloakJwtAuthenticationConverterTest' test`
Expected: falha (o `SecurityConfig` atual depende de `OrderSecurityFilter`/`jwt.secret`; o contexto do `@WebMvcTest` não sobe).

- [ ] **Step 5: Remover os filtros manuais e reescrever o `SecurityConfig`**

```bash
git rm src/main/java/com/example/ecommerce/order_service/infra/security/OrderSecurityFilter.java \
       src/main/java/com/example/ecommerce/order_service/infra/security/TokenService.java
```

`SecurityConfig.java` (substitui o conteúdo inteiro):

```java
package com.example.ecommerce.order_service.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll()
                        .requestMatchers("/api/order", "/api/order/**").authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter())))
                .build();
    }
}
```

- [ ] **Step 6: Renomear o parâmetro de identidade no `OrderService`**

Em `OrderService.java`:
- `public OrderResponseDTO createOrder(OrderRequestDTO request, String userEmail) {` → `public OrderResponseDTO createOrder(OrderRequestDTO request, String userId) {`
- `order.setUserID(userEmail);` → `order.setUserID(userId);`

(`findAll`/`findById` já usam `authentication.getName()`, que agora é o `sub`.)

Run: `./mvnw -q -Dtest='OrderSecurityTest,OrderServiceOwnershipTest,KeycloakJwtAuthenticationConverterTest' test`
Expected: todos PASS.

- [ ] **Step 7: Garantir que não sobrou referência ao segredo antigo**

```bash
grep -rnE "jwt\.secret|JWT_SECRET|auth0|TokenService|OrderSecurityFilter" src/ pom.xml
```

Expected: nenhuma linha.

- [ ] **Step 8: Commit**

```bash
cd ..
git add -A order-service/
git commit -m "feat(order): Resource Server do Keycloak e pedidos vinculados ao sub"
```

---

### Task 6: order-service → inventory com Client Credentials

**Files:**
- Modify: `order-service/pom.xml` (oauth2-client + dependências de teste)
- Modify: `order-service/src/main/resources/application.yml`
- Create: `order-service/src/main/java/com/example/ecommerce/order_service/infra/security/OAuth2ClientConfig.java`
- Modify: `order-service/src/main/java/com/example/ecommerce/order_service/services/OrderService.java` (só `409` vira estoque insuficiente)
- Modify: `order-service/src/main/java/com/example/ecommerce/order_service/infra/exceptionhandler/GlobalExceptionHandler.java` (`503` para falha de token)
- Test: `order-service/src/test/java/com/example/ecommerce/order_service/services/OrderServiceReserveErrorTest.java`
- Test: `order-service/src/test/java/com/example/ecommerce/order_service/OrderServiceToInventoryAuthIntegrationTest.java`

**Interfaces:**
- Consumes: `SCOPE_inventory:reserve` exigido pelo inventory (Task 4); client `order-service` do realm (Task 1); `OrderService.createOrder(OrderRequestDTO, String userId)` (Task 5).
- Produces: toda chamada Feign do order leva `Authorization: Bearer <token do client order-service>`.

- [ ] **Step 1: Dependências**

Em `order-service/pom.xml`, dentro de `<dependencies>`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-client</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-rabbitmq</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.wiremock</groupId>
			<artifactId>wiremock-standalone</artifactId>
			<version>3.13.2</version>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Teste do mapeamento de erros da reserva (falhando)**

`OrderServiceReserveErrorTest.java`:

```java
package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.client.InventoryClient;
import com.example.ecommerce.order_service.client.ProductClient;
import com.example.ecommerce.order_service.dtos.request.OrderItemRequestDTO;
import com.example.ecommerce.order_service.dtos.request.OrderRequestDTO;
import com.example.ecommerce.order_service.dtos.response.ProductDTO;
import com.example.ecommerce.order_service.exceptions.InsufficientStockException;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceReserveErrorTest {

    private static final Request RESERVE = Request.create(Request.HttpMethod.POST, "/api/inventory/reserve",
            Map.of(), null, StandardCharsets.UTF_8, null);
    private static final OrderRequestDTO ORDER = new OrderRequestDTO(List.of(new OrderItemRequestDTO("p1", 1)));

    @Mock
    private ProductClient productClient;

    @Mock
    private InventoryClient inventoryClient;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void productExists() {
        when(productClient.getProductById("p1")).thenReturn(new ProductDTO("p1", "Teclado", BigDecimal.TEN));
    }

    @Test
    void conflictMeansInsufficientStock() {
        doThrow(new FeignException.Conflict("conflict", RESERVE, null, null)).when(inventoryClient).reserve(any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void reserveForbiddenIsNotReportedAsInsufficientStock() {
        doThrow(new FeignException.Forbidden("forbidden", RESERVE, null, null)).when(inventoryClient).reserve(any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(FeignException.Forbidden.class);
    }

    @Test
    void reserveUnauthorizedIsNotReportedAsInsufficientStock() {
        doThrow(new FeignException.Unauthorized("unauthorized", RESERVE, null, null)).when(inventoryClient).reserve(any());

        assertThatThrownBy(() -> orderService.createOrder(ORDER, "user-sub"))
                .isInstanceOf(FeignException.Unauthorized.class);
    }
}
```

Run: `cd order-service && ./mvnw -q -Dtest=OrderServiceReserveErrorTest test`
Expected: `conflictMeansInsufficientStock` PASS; os dois outros FAIL (hoje qualquer `FeignException` vira `InsufficientStockException`).

- [ ] **Step 3: Corrigir o mapeamento em `OrderService.createOrder`**

Troque:

```java
        try {
            inventoryClient.reserve(new InventoryReserveRequestDTO(reserveItems));
        } catch (FeignException e) {
            throw new InsufficientStockException("Estoque insuficiente para um ou mais itens do pedido");
        }
```

por:

```java
        try {
            inventoryClient.reserve(new InventoryReserveRequestDTO(reserveItems));
        } catch (FeignException.Conflict e) {
            // Só o 409 do inventory significa falta de estoque. 401/403 (token de serviço rejeitado)
            // e demais falhas seguem para o GlobalExceptionHandler, que responde 502.
            throw new InsufficientStockException("Estoque insuficiente para um ou mais itens do pedido");
        }
```

Run: `./mvnw -q -Dtest=OrderServiceReserveErrorTest test`
Expected: 3 PASS.

- [ ] **Step 4: Configuração do client OAuth2 e do Feign**

Em `order-service/src/main/resources/application.yml`, complete o bloco `spring.security.oauth2` criado na Task 5 e adicione `spring.cloud.openfeign`:

```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ecommerce}
          jwk-set-uri: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}/realms/ecommerce/protocol/openid-connect/certs
          audiences: ecommerce-api
      client:
        registration:
          order-service:
            provider: keycloak
            client-id: order-service
            client-secret: ${KC_ORDER_SERVICE_CLIENT_SECRET}
            authorization-grant-type: client_credentials
        provider:
          keycloak:
            token-uri: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}/realms/ecommerce/protocol/openid-connect/token

  cloud:
    openfeign:
      oauth2:
        # Adiciona o token de serviço (client_credentials) em todas as chamadas Feign.
        enabled: true
        clientRegistrationId: order-service
```

- [ ] **Step 5: Bean do `OAuth2AuthorizedClientManager`**

`OAuth2ClientConfig.java`:

```java
package com.example.ecommerce.order_service.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration
public class OAuth2ClientConfig {

    /**
     * Manager baseado em serviço (não em requisição HTTP): funciona em qualquer thread, inclusive
     * fora do contexto da requisição do usuário. Faz cache do token e pede outro quando expira.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository clientRegistrations,
                                                                 OAuth2AuthorizedClientService authorizedClients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build());
        return manager;
    }
}
```

- [ ] **Step 6: `503` quando não for possível obter o token de serviço**

Em `GlobalExceptionHandler.java`, adicione o import `org.springframework.security.oauth2.core.OAuth2AuthorizationException` e, antes do handler genérico de `Exception`:

```java
    @ExceptionHandler(OAuth2AuthorizationException.class)
    public ResponseEntity<ProblemDetail> handleServiceTokenFailure(OAuth2AuthorizationException ex) {
        log.error("Não foi possível obter o token de serviço no Keycloak: {}", ex.getError().getErrorCode());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Serviço de autenticação indisponível");
    }
```

- [ ] **Step 7: Teste de integração ponta a ponta do token de serviço**

`OrderServiceToInventoryAuthIntegrationTest.java`:

```java
package com.example.ecommerce.order_service;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sobe o order-service com Postgres e RabbitMQ reais; Keycloak, product e inventory são simulados
 * pelo WireMock. Prova que a chamada ao inventory leva o token obtido via client_credentials.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "KC_ORDER_SERVICE_CLIENT_SECRET=order-secret-test",
        "DB_PASSWORD=unused"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderServiceToInventoryAuthIntegrationTest {

    private static final String TOKEN_PATH = "/realms/ecommerce/protocol/openid-connect/token";
    private static final String ORDER_BODY = """
            {"items":[{"productId":"p1","quantity":1}]}
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

    static final WireMockServer WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("KEYCLOAK_INTERNAL_URL", WIREMOCK::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.inventory-service.url", WIREMOCK::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.product-service.url", WIREMOCK::baseUrl);
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void stubs() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get("/api/product/p1").willReturn(okJson("""
                {"id":"p1","name":"Teclado","price":10.00}
                """)));
        WIREMOCK.stubFor(post("/api/inventory/reserve").willReturn(aResponse().withStatus(204)));
    }

    @Test
    void reserveCallCarriesClientCredentialsToken() throws Exception {
        WIREMOCK.stubFor(post(TOKEN_PATH).willReturn(okJson("""
                {"access_token":"svc-token","token_type":"Bearer","expires_in":300}
                """)));

        mvc.perform(post("/api/order")
                        .with(jwt().jwt(j -> j.subject("user-sub")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isCreated());

        WIREMOCK.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=client_credentials")));
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/api/inventory/reserve"))
                .withHeader("Authorization", equalTo("Bearer svc-token")));
    }

    @Test
    void keycloakDownReturns503() throws Exception {
        WIREMOCK.stubFor(post(TOKEN_PATH).willReturn(serverError()));

        mvc.perform(post("/api/order")
                        .with(jwt().jwt(j -> j.subject("user-sub")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isServiceUnavailable());

        WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/api/inventory/reserve")));
    }
}
```

Observações para o implementador:
- Os testes rodam em ordem indefinida e o token fica em cache no `OAuth2AuthorizedClientService`. Se `keycloakDownReturns503` rodar depois do outro teste, o token em cache evita a chamada ao Keycloak. Para isolar, anote a classe com `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)` (`org.springframework.test.annotation.DirtiesContext`).
- No Testcontainers 2.0 os módulos ficam em `org.testcontainers.postgresql.PostgreSQLContainer` e `org.testcontainers.rabbitmq.RabbitMQContainer`. Se a compilação não encontrar essas classes, use `org.testcontainers.containers.PostgreSQLContainer<?>` e `org.testcontainers.containers.RabbitMQContainer`.
- A chamada ao product (`GET /api/product/p1`) também recebe o token de serviço; a rota é pública e aceita.

Run: `./mvnw -q -Dtest='OrderServiceToInventoryAuthIntegrationTest,OrderServiceReserveErrorTest,OrderSecurityTest,OrderServiceOwnershipTest' test`
Expected: todos PASS.

- [ ] **Step 8: Commit**

```bash
cd ..
git add -A order-service/
git commit -m "feat(order): chama o inventory com token de serviço via client_credentials"
```

---

### Task 7: api-gateway — cadeia Bearer (Resource Server) e remoção do filtro manual

**Files:**
- Modify: `api-gateway/pom.xml`
- Modify: `api-gateway/src/main/resources/application.yml`
- Delete: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/JwtAuthenticationFilter.java`
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/KeycloakJwtAuthenticationConverter.java`
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/GatewayAuthorizationRules.java`
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/SecurityConfig.java`
- Create: `api-gateway/src/test/resources/application-test.yml`
- Test: `api-gateway/src/test/java/com/example/ecommerce/api_gateway/infra/security/GatewayTestSupport.java`
- Test: `api-gateway/src/test/java/com/example/ecommerce/api_gateway/infra/security/BearerChainTest.java`

**Interfaces:**
- Produces: `GatewayAuthorizationRules.apply(registry)` (reusado pela cadeia de sessão na Task 8); `SecurityConfig.bearerChain` com `@Order(1)`; classe base de teste `GatewayTestSupport` (Redis + WireMock), reusada na Task 8.

- [ ] **Step 1: Dependências**

Em `api-gateway/pom.xml`, **remova** o bloco `com.auth0:java-jwt` e adicione:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-client</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-session-data-redis</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.wiremock</groupId>
			<artifactId>wiremock-standalone</artifactId>
			<version>3.13.2</version>
			<scope>test</scope>
		</dependency>
```

(`testcontainers-junit-jupiter` já foi adicionado na Task 2.)

- [ ] **Step 2: `application.yml` do gateway**

Substitua o arquivo inteiro por:

```yaml
server:
  port: 8080
  # Respeita X-Forwarded-* (proxy do Angular CLI e reverse proxy de produção) ao montar redirect_uri.
  forward-headers-strategy: framework
  servlet:
    session:
      timeout: 30m
      cookie:
        http-only: true
        same-site: lax
        secure: ${SESSION_COOKIE_SECURE:false}

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      server:
        webmvc:
          routes:
            - id: product-service
              uri: lb://product-service
              predicates:
                - Path=/api/product/**
              filters:
                - RemoveRequestHeader=Cookie
            - id: product-service-root
              uri: lb://product-service
              predicates:
                - Path=/api/product
              filters:
                - RemoveRequestHeader=Cookie
            - id: order-service
              uri: lb://order-service
              predicates:
                - Path=/api/order/**
              filters:
                - RemoveRequestHeader=Cookie
            - id: order-service-root
              uri: lb://order-service
              predicates:
                - Path=/api/order
              filters:
                - RemoveRequestHeader=Cookie
            - id: inventory-service
              uri: lb://inventory-service
              predicates:
                - Path=/api/inventory/**
              filters:
                - RemoveRequestHeader=Cookie
            - id: inventory-service-root
              uri: lb://inventory-service
              predicates:
                - Path=/api/inventory
              filters:
                - RemoveRequestHeader=Cookie
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ecommerce}
          jwk-set-uri: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}/realms/ecommerce/protocol/openid-connect/certs
          audiences: ecommerce-api
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD}
  session:
    redis:
      namespace: ecommerce:gateway:session

ecommerce:
  bff:
    frontend-url: ${FRONTEND_URL:http://localhost:4200}
    keycloak-issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ecommerce}
    keycloak-internal-url: ${KEYCLOAK_INTERNAL_URL:http://localhost:8180}
    client-id: ecommerce-bff
    client-secret: ${KC_BFF_CLIENT_SECRET}

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true

logging:
  level:
    org.springframework.cloud.gateway: INFO
    org.springframework.web.servlet.function: INFO

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  metrics:
    tags:
      application: ${spring.application.name}
  tracing:
    sampling:
      probability: 1.0
    export:
      zipkin:
        endpoint: http://localhost:9411/api/v2/spans
```

Mudanças em relação ao anterior: rotas `/auth/**` removidas, `RemoveRequestHeader=Cookie` em todas as rotas, `jwt.secret` removido, logging `DEBUG` → `INFO`, endpoint `gateway` fora do Actuator, blocos de segurança/Redis/BFF adicionados. O bloco `ecommerce.bff` é consumido na Task 8.

- [ ] **Step 3: Perfil de teste**

`api-gateway/src/test/resources/application-test.yml`:

```yaml
spring:
  cloud:
    discovery:
      enabled: false
    gateway:
      server:
        webmvc:
          routes:
            - id: product-service
              uri: ${backend.url}
              predicates:
                - Path=/api/product/**
              filters:
                - RemoveRequestHeader=Cookie
            - id: product-service-root
              uri: ${backend.url}
              predicates:
                - Path=/api/product
              filters:
                - RemoveRequestHeader=Cookie
            - id: order-service
              uri: ${backend.url}
              predicates:
                - Path=/api/order/**
              filters:
                - RemoveRequestHeader=Cookie
            - id: order-service-root
              uri: ${backend.url}
              predicates:
                - Path=/api/order
              filters:
                - RemoveRequestHeader=Cookie
            - id: inventory-service
              uri: ${backend.url}
              predicates:
                - Path=/api/inventory/**
              filters:
                - RemoveRequestHeader=Cookie
            - id: inventory-service-root
              uri: ${backend.url}
              predicates:
                - Path=/api/inventory
              filters:
                - RemoveRequestHeader=Cookie

eureka:
  client:
    enabled: false

KC_BFF_CLIENT_SECRET: bff-secret-test
```

- [ ] **Step 4: Base de teste com Redis e WireMock**

`GatewayTestSupport.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** Gateway completo com Redis real (sessões) e WireMock no lugar dos microsserviços. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class GatewayTestSupport {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-alpine3.21")
            .withCommand("redis-server", "--requirepass", "redis-test")
            .withExposedPorts(6379);

    static final WireMockServer BACKEND = new WireMockServer(wireMockConfig().dynamicPort());

    // Compartilhados por todas as classes de teste do gateway (mesmo contexto Spring em cache);
    // o Ryuk do Testcontainers encerra o Redis e a JVM de teste encerra o WireMock.
    static {
        REDIS.start();
        BACKEND.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "redis-test");
        registry.add("backend.url", BACKEND::baseUrl);
    }

    @Autowired
    protected MockMvc mvc;

    @BeforeEach
    void resetBackend() {
        BACKEND.resetAll();
        BACKEND.stubFor(any(urlMatching("/api/.*")).willReturn(okJson("{}")));
    }
}
```

- [ ] **Step 5: Teste da cadeia Bearer (falhando)**

`BearerChainTest.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BearerChainTest extends GatewayTestSupport {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("customer-token")).thenReturn(Jwt.withTokenValue("customer-token")
                .header("alg", "RS256").subject("user-sub")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .build());
        when(jwtDecoder.decode("garbage")).thenThrow(new BadJwtException("invalid"));
    }

    @Test
    void validBearerIsForwardedUnchangedWithoutCreatingSession() throws Exception {
        MvcResult result = mvc.perform(get("/api/order").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isOk())
                .andReturn();

        BACKEND.verify(getRequestedFor(urlEqualTo("/api/order"))
                .withHeader("Authorization", equalTo("Bearer customer-token")));
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION"));
    }

    @Test
    void invalidBearerIs401AndNotForwarded() throws Exception {
        mvc.perform(get("/api/order").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());

        BACKEND.verify(0, getRequestedFor(urlEqualTo("/api/order")));
    }

    @Test
    void reserveAndReleaseAreDeniedAtTheEdge() throws Exception {
        mvc.perform(post("/api/inventory/reserve").header("Authorization", "Bearer customer-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/release").header("Authorization", "Bearer customer-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        BACKEND.verify(0, postRequestedFor(urlMatching("/api/inventory/.*")));
    }

    @Test
    void bffEndpointsAreNotAvailableToBearerClients() throws Exception {
        mvc.perform(get("/bff/me").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCanReadCatalog() throws Exception {
        mvc.perform(get("/api/product")).andExpect(status().isOk());
    }

    @Test
    void gatewayActuatorEndpointIsNotExposed() throws Exception {
        mvc.perform(get("/actuator/gateway/routes").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
    }
}
```

Run: `cd api-gateway && ./mvnw -q -Dtest=BearerChainTest test`
Expected: FAIL (não há `SecurityConfig`; o contexto também falha porque o `JwtAuthenticationFilter` exige `jwt.secret`).

- [ ] **Step 6: Remover o filtro manual e criar conversor e regras**

```bash
git rm src/main/java/com/example/ecommerce/api_gateway/infra/security/JwtAuthenticationFilter.java
```

`KeycloakJwtAuthenticationConverter.java` (pacote `com.example.ecommerce.api_gateway.infra.security`):

```java
package com.example.ecommerce.api_gateway.infra.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converte o JWT do Keycloak em autenticação do Spring: scopes viram SCOPE_*, realm roles
 * (claim realm_access.roles) viram ROLE_*, e o nome da autenticação é o sub (UUID imutável).
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(scopesConverter.convert(jwt));
        for (String role : realmRoles(jwt.getClaims())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /** Lê realm_access.roles de um conjunto de claims (access token ou ID token); ignora formatos inesperados. */
    static Set<String> realmRoles(Map<String, Object> claims) {
        Set<String> roles = new HashSet<>();
        if (claims.get("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> names) {
            for (Object name : names) {
                if (name instanceof String role) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }
}
```

`GatewayAuthorizationRules.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Regras grossas de borda, comuns às cadeias Bearer e de sessão. As regras finas (roles, dono do
 * pedido) ficam em cada microsserviço, que valida o token sozinho.
 */
final class GatewayAuthorizationRules {

    private GatewayAuthorizationRules() {
    }

    static AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry apply(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        return authorize
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/error").permitAll()
                // Contrato interno order → inventory; nunca exposto pela borda.
                .requestMatchers("/api/inventory/reserve", "/api/inventory/release").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/product", "/api/product/**", "/api/inventory", "/api/inventory/**").permitAll()
                .requestMatchers("/api/**").authenticated();
    }
}
```

`SecurityConfig.java` (só a cadeia Bearer nesta task; a Task 8 adiciona a de sessão):

```java
package com.example.ecommerce.api_gateway.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Requisições de clientes de API (Bruno, app mobile): têm o header Authorization: Bearer. */
    static final RequestMatcher BEARER_REQUEST = (HttpServletRequest request) -> {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    };

    @Bean
    @Order(1)
    public SecurityFilterChain bearerChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(BEARER_REQUEST)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> GatewayAuthorizationRules.apply(authorize)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter())))
                .build();
    }
}
```

Observação: até a Task 8, requisições sem Bearer caem na cadeia padrão do Spring Boot. O `spring-boot-starter-security-oauth2-client` exige uma `ClientRegistration`; se o contexto não subir por isso nesta task, avance o Step 3 da Task 8 (`BffClientRegistrationConfig`) para cá.

Run: `./mvnw -q -Dtest=BearerChainTest test`
Expected: 6 PASS (`anonymousCanReadCatalog` pode depender da cadeia de sessão; se falhar com 401/302 aqui, ele passa a valer ao final da Task 8 — registre e siga).

- [ ] **Step 7: Commit**

```bash
cd ..
git add -A api-gateway/
git commit -m "feat(gateway): valida Bearer do Keycloak e remove o filtro JWT manual"
```

---

### Task 8: api-gateway — BFF (login, sessão Redis, CSRF, relay, `/bff/me`, logout)

**Files:**
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/BffProperties.java`
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/BffClientRegistrationConfig.java`
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/SessionTokenRelayFilter.java`
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/BearerTokenRequestWrapper.java`
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/bff/BffUserController.java`
- Create: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/bff/BffUserResponse.java`
- Modify: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/infra/security/SecurityConfig.java` (adiciona `sessionChain`)
- Modify: `api-gateway/src/main/java/com/example/ecommerce/api_gateway/ApiGatewayApplication.java` (`@ConfigurationPropertiesScan`)
- Test: `api-gateway/src/test/java/com/example/ecommerce/api_gateway/infra/security/SessionTokenRelayFilterTest.java`
- Test: `api-gateway/src/test/java/com/example/ecommerce/api_gateway/infra/security/SessionChainTest.java`

**Interfaces:**
- Consumes: `GatewayAuthorizationRules.apply` e `KeycloakJwtAuthenticationConverter.realmRoles(Map<String,Object>)` (Task 7); `GatewayTestSupport` (Task 7); client `ecommerce-bff` (Task 1).
- Produces (contrato com o frontend Angular):
  - `GET /oauth2/authorization/keycloak` → inicia login.
  - `GET /bff/me` → `200 {"sub","name","email","roles":[...]}` ou `401`; grava o cookie `XSRF-TOKEN`.
  - `POST /logout` (form, com `_csrf` ou header `X-XSRF-TOKEN`) → encerra sessão e redireciona para o logout do Keycloak e depois para `FRONTEND_URL/`.
  - `/api/**` sem sessão → `401`; escrita sem CSRF → `403`.

- [ ] **Step 1: Propriedades do BFF**

`BffProperties.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ecommerce.bff")
public record BffProperties(
        String frontendUrl,
        String keycloakIssuerUri,
        String keycloakInternalUrl,
        String clientId,
        String clientSecret
) {
}
```

Em `ApiGatewayApplication.java`, adicione `@ConfigurationPropertiesScan` (import `org.springframework.boot.context.properties.ConfigurationPropertiesScan`) na classe principal.

- [ ] **Step 2: Teste unitário do relay (falhando)**

`SessionTokenRelayFilterTest.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionTokenRelayFilterTest {

    private final OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    private final SessionTokenRelayFilter filter = new SessionTokenRelayFilter(manager);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void relayUsesAuthorizedClientFromSession() throws Exception {
        loginWithSession();
        when(manager.authorize(any())).thenReturn(authorizedClient("relay-token"));
        var request = new MockHttpServletRequest("GET", "/api/order");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        var forwarded = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(forwarded.capture(), any());
        assertThat(forwarded.getValue().getHeader("Authorization")).isEqualTo("Bearer relay-token");
        assertThat(java.util.Collections.list(forwarded.getValue().getHeaders("Authorization")))
                .containsExactly("Bearer relay-token");
    }

    @Test
    void relayWithoutAuthorizedClientReturns401AndInvalidatesSession() throws Exception {
        loginWithSession();
        when(manager.authorize(any())).thenReturn(null);
        var session = new MockHttpSession();
        var request = new MockHttpServletRequest("GET", "/api/order");
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(chain);
    }

    @Test
    void bearerRequestsPassThroughUntouched() throws Exception {
        Jwt jwt = Jwt.withTokenValue("api-token").header("alg", "RS256").subject("s").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var request = new MockHttpServletRequest("GET", "/api/order");
        request.addHeader("Authorization", "Bearer api-token");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(request, any());
        verifyNoInteractions(manager);
    }

    @Test
    void nonApiPathsAreNotRelayed() throws Exception {
        loginWithSession();
        var request = new MockHttpServletRequest("GET", "/bff/me");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(request, any());
        verifyNoInteractions(manager);
    }

    private static void loginWithSession() {
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OIDC_USER")), Map.of("sub", "user-sub"), "sub");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
    }

    private static OAuth2AuthorizedClient authorizedClient(String token) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("keycloak")
                .clientId("ecommerce-bff")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://kc/auth").tokenUri("http://kc/token")
                .build();
        var accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                Instant.now(), Instant.now().plusSeconds(300));
        return new OAuth2AuthorizedClient(registration, "user-sub", accessToken);
    }
}
```

Run: `cd api-gateway && ./mvnw -q -Dtest=SessionTokenRelayFilterTest test`
Expected: FAIL de compilação.

- [ ] **Step 3: Registro do client e manager (tokens na sessão)**

`BffClientRegistrationConfig.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import java.net.URI;
import java.util.Map;

@Configuration
public class BffClientRegistrationConfig {

    /**
     * Declarado em Java para informar issuer e end_session_endpoint sem discovery na subida:
     * o navegador usa a URL pública do Keycloak, o gateway (back-channel) usa a URL interna.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(BffProperties properties) {
        String realmPath = URI.create(properties.keycloakIssuerUri()).getPath();
        String publicEndpoints = properties.keycloakIssuerUri() + "/protocol/openid-connect";
        String internalEndpoints = properties.keycloakInternalUrl() + realmPath + "/protocol/openid-connect";

        ClientRegistration keycloak = ClientRegistration.withRegistrationId("keycloak")
                .clientName("Keycloak")
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .issuerUri(properties.keycloakIssuerUri())
                .authorizationUri(publicEndpoints + "/auth")
                .tokenUri(internalEndpoints + "/token")
                .jwkSetUri(internalEndpoints + "/certs")
                .userInfoUri(internalEndpoints + "/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .providerConfigurationMetadata(Map.of("end_session_endpoint", publicEndpoints + "/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(keycloak);
    }

    /** Tokens guardados na sessão HTTP (Redis): sobrevivem a restart e são compartilhados entre instâncias. */
    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository clientRegistrations,
                                                                 OAuth2AuthorizedClientRepository authorizedClients) {
        var manager = new DefaultOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build());
        return manager;
    }
}
```

- [ ] **Step 4: Relay do token da sessão**

`BearerTokenRequestWrapper.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.http.HttpHeaders;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/** Expõe Authorization: Bearer <token> no lugar de qualquer Authorization recebido do navegador. */
class BearerTokenRequestWrapper extends HttpServletRequestWrapper {

    private final String authorization;

    BearerTokenRequestWrapper(HttpServletRequest request, String accessToken) {
        super(request);
        this.authorization = "Bearer " + accessToken;
    }

    @Override
    public String getHeader(String name) {
        return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? authorization : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                ? Collections.enumeration(Set.of(authorization))
                : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
        names.add(HttpHeaders.AUTHORIZATION);
        return Collections.enumeration(names);
    }
}
```

`SessionTokenRelayFilter.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * BFF: para requisições /api/** autenticadas por sessão, injeta o access token guardado na sessão
 * (renovando com o refresh token quando expira). Requisições Bearer passam intactas.
 * Roda depois do springSecurityFilterChain (registrado em SecurityConfig).
 */
public class SessionTokenRelayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenRelayFilter.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public SessionTokenRelayFilter(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2)) {
            chain.doFilter(request, response);
            return;
        }

        OAuth2AuthorizedClient client = authorize(oauth2, request, response);
        if (client == null) {
            // Refresh token expirado/revogado: a sessão não serve mais; o frontend deve refazer o login.
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(new BearerTokenRequestWrapper(request, client.getAccessToken().getTokenValue()), response);
    }

    private OAuth2AuthorizedClient authorize(OAuth2AuthenticationToken authentication,
                                             HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(authentication.getAuthorizedClientRegistrationId())
                .principal(authentication)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();
        try {
            return authorizedClientManager.authorize(authorizeRequest);
        } catch (OAuth2AuthorizationException ex) {
            log.warn("Falha ao renovar o token da sessão: {}", ex.getError().getErrorCode());
            return null;
        }
    }
}
```

Run: `./mvnw -q -Dtest=SessionTokenRelayFilterTest test`
Expected: 4 PASS.

- [ ] **Step 5: Endpoint `/bff/me`**

`BffUserResponse.java` (pacote `com.example.ecommerce.api_gateway.bff`):

```java
package com.example.ecommerce.api_gateway.bff;

import java.util.Set;

public record BffUserResponse(String sub, String name, String email, Set<String> roles) {
}
```

`BffUserController.java`:

```java
package com.example.ecommerce.api_gateway.bff;

import com.example.ecommerce.api_gateway.infra.security.KeycloakRoles;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BffUserController {

    /**
     * Usuário da sessão para o frontend montar a interface. Acessar o CsrfToken força a gravação
     * do cookie XSRF-TOKEN, que o HttpClient do Angular devolve no header X-XSRF-TOKEN nas escritas.
     */
    @GetMapping("/bff/me")
    public BffUserResponse me(@AuthenticationPrincipal OidcUser user, CsrfToken csrfToken) {
        csrfToken.getToken();
        return new BffUserResponse(user.getSubject(), user.getFullName(), user.getEmail(),
                KeycloakRoles.fromClaims(user.getClaims()));
    }
}
```

Como `KeycloakJwtAuthenticationConverter.realmRoles` é package-private, exponha a leitura de roles numa classe pública do pacote de segurança — `KeycloakRoles.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import java.util.Map;
import java.util.Set;

public final class KeycloakRoles {

    private KeycloakRoles() {
    }

    /** Realm roles (claim realm_access.roles) de um access token ou ID token do Keycloak. */
    public static Set<String> fromClaims(Map<String, Object> claims) {
        return KeycloakJwtAuthenticationConverter.realmRoles(claims);
    }
}
```

- [ ] **Step 6: Cadeia de sessão e registro do filtro**

Em `SecurityConfig.java`, acrescente os imports:

```java
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
```

e os beans:

```java
    @Bean
    @Order(2)
    public SecurityFilterChain sessionChain(HttpSecurity http,
                                            ClientRegistrationRepository clientRegistrations,
                                            BffProperties properties) throws Exception {
        var authorizationRequestResolver =
                new DefaultOAuth2AuthorizationRequestResolver(clientRegistrations, "/oauth2/authorization");
        // O client ecommerce-bff é confidencial, mas o realm exige PKCE S256 em todos os clients de usuário.
        authorizationRequestResolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        var logoutSuccessHandler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        logoutSuccessHandler.setPostLogoutRedirectUri(properties.frontendUrl() + "/");

        var unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        var paths = PathPatternRequestMatcher.withDefaults();

        return http
                .authorizeHttpRequests(authorize -> GatewayAuthorizationRules.apply(authorize)
                        .requestMatchers("/bff/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequestResolver))
                        .defaultSuccessUrl(properties.frontendUrl() + "/", true))
                .logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler))
                // Cookie XSRF-TOKEN legível pelo JS + header X-XSRF-TOKEN (padrão do Spring Security para SPA).
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(unauthorized, paths.matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(unauthorized, paths.matcher("/bff/**")))
                .build();
    }

    @Bean
    public FilterRegistrationBean<SessionTokenRelayFilter> sessionTokenRelayFilter(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        var registration = new FilterRegistrationBean<>(new SessionTokenRelayFilter(authorizedClientManager));
        // Depois do springSecurityFilterChain (ordem -100), para enxergar a autenticação da sessão.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }
```

Na cadeia Bearer, troque `GatewayAuthorizationRules.apply(authorize).anyRequest().denyAll()` por:

```java
                .authorizeHttpRequests(authorize -> GatewayAuthorizationRules.apply(authorize)
                        .requestMatchers("/bff/**").denyAll()
                        .anyRequest().denyAll())
```

(Explícito: `/bff/**` é só para o navegador.)

Se `csrf.spa()` não compilar (método introduzido no Spring Security 6.4; o Boot 4.0.2 traz o 7.0.2, então deve existir), use o equivalente explícito da documentação do Spring Security: `csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())`, criando a classe `SpaCsrfTokenRequestHandler` exatamente como na seção "Single-Page Applications" da documentação de CSRF.

- [ ] **Step 7: Teste da cadeia de sessão (falhando parcialmente antes do Step 6, passando depois)**

`SessionChainTest.java`:

```java
package com.example.ecommerce.api_gateway.infra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import jakarta.servlet.http.Cookie;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionChainTest extends GatewayTestSupport {

    @MockitoBean
    private OAuth2AuthorizedClientManager authorizedClientManager;

    @Autowired
    private ClientRegistrationRepository clientRegistrations;

    @BeforeEach
    void authorizedClient() {
        var registration = clientRegistrations.findByRegistrationId("keycloak");
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "relay-token",
                Instant.now(), Instant.now().plusSeconds(300));
        when(authorizedClientManager.authorize(any())).thenReturn(new OAuth2AuthorizedClient(registration, "user-sub", token));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customer() {
        return oidcLogin()
                .clientRegistration(clientRegistrations.findByRegistrationId("keycloak"))
                .idToken(t -> t.subject("user-sub")
                        .claim("name", "Ana Cliente")
                        .claim("email", "ana@ecommerce.local")
                        .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
    }

    @Test
    void apiWithoutSessionIs401NotRedirect() throws Exception {
        mvc.perform(get("/api/order")).andExpect(status().isUnauthorized());
        mvc.perform(get("/bff/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void browserNavigationWithoutSessionStartsLogin() throws Exception {
        mvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("http://localhost:8180/realms/ecommerce/protocol/openid-connect/auth"),
                        org.hamcrest.Matchers.containsString("code_challenge_method=S256"))));
    }

    @Test
    void sessionRequestIsRelayedWithBearerAndWithoutCookie() throws Exception {
        mvc.perform(get("/api/order").with(customer()).cookie(new Cookie("SESSION", "abc")))
                .andExpect(status().isOk());

        BACKEND.verify(getRequestedFor(urlEqualTo("/api/order"))
                .withHeader("Authorization", equalTo("Bearer relay-token"))
                .withoutHeader("Cookie"));
    }

    @Test
    void writeWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/order").with(customer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        BACKEND.verify(0, postRequestedFor(urlEqualTo("/api/order")));
    }

    @Test
    void writeWithCsrfIsRelayed() throws Exception {
        mvc.perform(post("/api/order").with(customer()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        BACKEND.verify(postRequestedFor(urlEqualTo("/api/order"))
                .withHeader("Authorization", equalTo("Bearer relay-token")));
    }

    @Test
    void meReturnsUserAndSetsCsrfCookie() throws Exception {
        var result = mvc.perform(get("/bff/me").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("user-sub"))
                .andExpect(jsonPath("$.name").value("Ana Cliente"))
                .andExpect(jsonPath("$.email").value("ana@ecommerce.local"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andReturn();

        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN").isHttpOnly()).isFalse();
    }

    @Test
    void reserveIsDeniedEvenWithSession() throws Exception {
        mvc.perform(post("/api/inventory/reserve").with(customer()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRedirectsToKeycloakEndSession() throws Exception {
        mvc.perform(post("/logout").with(customer()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("http://localhost:8180/realms/ecommerce/protocol/openid-connect/logout"),
                        org.hamcrest.Matchers.containsString("post_logout_redirect_uri=http://localhost:4200/"))));
    }
}
```

Run: `./mvnw -q -Dtest='SessionChainTest,SessionTokenRelayFilterTest,BearerChainTest' test`
Expected: todos PASS.

- [ ] **Step 8: Conferir o namespace das sessões no Redis**

Com a infraestrutura da Task 1 rodando, suba o gateway pela IDE (ou `./mvnw spring-boot:run` com as variáveis do `.env`), abra `http://localhost:8080/oauth2/authorization/keycloak` no navegador, faça login com o admin e rode:

```bash
docker exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "ecommerce:gateway:session*"' | head
```

Expected: pelo menos uma chave. Se vier vazio e existirem chaves `spring:session:*`, o Spring Boot 4 usa outra propriedade para o namespace: troque `spring.session.redis.namespace` por `spring.session.data.redis.namespace` no `application.yml` e repita.

- [ ] **Step 9: Commit**

```bash
cd ..
git add -A api-gateway/
git commit -m "feat(gateway): BFF com login OIDC, sessão no Redis, CSRF e relay do token"
```

---

### Task 9: Wiring no Docker Compose e remoção do auth-service

**Files:**
- Modify: `docker-compose.yml`
- Modify: `init-db.sh` (remove `auth_service`)
- Modify: `.env.example`
- Modify: `monitoring/prometheus.yml`
- Delete: `auth-service/` (pasta inteira)

**Interfaces:**
- Consumes: variáveis definidas nas Tasks 1, 5, 6, 7 e 8.

- [ ] **Step 1: Variáveis dos serviços no compose**

`api-gateway`:

```yaml
  api-gateway:
    build: ./api-gateway
    container_name: api-gateway
    ports:
      - "8080:8080"
    environment:
      - EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://eureka-server:8761/eureka/
      - MANAGEMENT_TRACING_EXPORT_ZIPKIN_ENDPOINT=http://zipkin:9411/api/v2/spans
      - KEYCLOAK_ISSUER_URI=http://localhost:8180/realms/ecommerce
      - KEYCLOAK_INTERNAL_URL=http://keycloak:8080
      - KC_BFF_CLIENT_SECRET=${KC_BFF_CLIENT_SECRET}
      - REDIS_HOST=redis
      - REDIS_PASSWORD=${REDIS_PASSWORD}
      - FRONTEND_URL=${FRONTEND_URL:-http://localhost:4200}
    depends_on:
      eureka-server:
        condition: service_healthy
      keycloak:
        condition: service_healthy
      redis:
        condition: service_healthy
```

(mantenha `healthcheck` e `restart`.)

`order-service`: remova `- JWT_SECRET=${JWT_SECRET}` e adicione:

```yaml
      - KEYCLOAK_ISSUER_URI=http://localhost:8180/realms/ecommerce
      - KEYCLOAK_INTERNAL_URL=http://keycloak:8080
      - KC_ORDER_SERVICE_CLIENT_SECRET=${KC_ORDER_SERVICE_CLIENT_SECRET}
```

e em `depends_on`:

```yaml
      keycloak:
        condition: service_healthy
```

`product-service` e `inventory-service`: adicione

```yaml
      - KEYCLOAK_ISSUER_URI=http://localhost:8180/realms/ecommerce
      - KEYCLOAK_INTERNAL_URL=http://keycloak:8080
```

e `keycloak: condition: service_healthy` em `depends_on`.

- [ ] **Step 2: Remover o auth-service**

```bash
git rm -r auth-service
```

No `docker-compose.yml`, apague o serviço `auth-service:` inteiro. No `init-db.sh`, apague a linha `CREATE DATABASE auth_service;`. No `.env.example` e no `.env` local, apague `JWT_SECRET`, `ADMIN_EMAIL` e `ADMIN_PASSWORD`.

```bash
grep -rnE "auth-service|auth_service|JWT_SECRET|ADMIN_EMAIL|ADMIN_PASSWORD|java-jwt" \
  --exclude-dir=.git --exclude-dir=target --exclude-dir=docs --exclude-dir=bruno .
```

Expected: nenhuma linha (Bruno e docs são tratados nas Tasks 10 e 11).

- [ ] **Step 3: Prometheus coleta o Keycloak**

Em `monitoring/prometheus.yml`, ao fim de `scrape_configs`:

```yaml
  - job_name: 'keycloak'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['keycloak:9000']
```

- [ ] **Step 4: Subir tudo e verificar**

```bash
docker compose config --quiet && echo "compose ok"
docker compose up -d --build
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
```

Expected: `compose ok`; todos os containers `healthy` (o `api-gateway` sobe por último). O database `auth_service` continua no volume já criado; remova-o com `docker exec postgres psql -U postgres -c 'DROP DATABASE auth_service'` (confirme com o usuário).

- [ ] **Step 5: Commit**

```bash
git add -A docker-compose.yml init-db.sh .env.example monitoring/prometheus.yml
git commit -m "feat!: remove o auth-service e conecta os serviços ao Keycloak no compose"
```

---

### Task 10: Collection do Bruno

**Files:**
- Delete: `bruno/auth-service/`, `bruno/environments/AuthDirect.bru`, `bruno/order-service/order-service-errors/Register Second User.bru`, `bruno/order-service/order-service-errors/Login Second User.bru`
- Create: `bruno/keycloak/folder.bru`, `bruno/keycloak/Login Customer.bru`, `bruno/keycloak/Login Admin.bru`, `bruno/keycloak/Login Second User.bru`
- Modify: `bruno/environments/*.bru`
- Modify: `bruno/inventory-service/Reserve Stock.bru`, `bruno/inventory-service/Release Stock.bru`
- Modify: `docs {}` de requests que citam `auth-service`, `JwtAuthenticationFilter`, `OrderSecurityFilter`, email como userId

**Interfaces:**
- Mantém as variáveis já usadas pelas requests: `{{accessToken}}`, `{{adminAccessToken}}`, `{{secondUserAccessToken}}`. Só muda quem as preenche.

- [ ] **Step 1: Remover as requests do auth-service**

```bash
git rm -r bruno/auth-service bruno/environments/AuthDirect.bru \
  "bruno/order-service/order-service-errors/Register Second User.bru" \
  "bruno/order-service/order-service-errors/Login Second User.bru"
```

- [ ] **Step 2: Ambientes**

Em cada arquivo de `bruno/environments/` (`Gateway.bru`, `InventoryDirect.bru`, `OrderDirect.bru`, `ProductDirect.bru`), mantenha o `baseUrl` e acrescente `keycloakUrl` e a variável secreta do client de serviço. Exemplo para `Gateway.bru`:

```
vars {
  baseUrl: http://localhost:8080
  keycloakUrl: http://localhost:8180
}
vars:secret [
  orderServiceClientSecret
]
```

(O valor de `orderServiceClientSecret` é preenchido localmente no Bruno com o `KC_ORDER_SERVICE_CLIENT_SECRET` do `.env`; segredos `vars:secret` não são gravados no arquivo.)

- [ ] **Step 3: Requests de login via Keycloak (Authorization Code + PKCE)**

`bruno/keycloak/folder.bru`:

```
meta {
  name: keycloak
  seq: 1
}
```

`bruno/keycloak/Login Customer.bru`:

```
meta {
  name: Login Customer
  type: http
  seq: 1
}

get {
  url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/userinfo
  body: none
  auth: oauth2
}

auth:oauth2 {
  grant_type: authorization_code
  callback_url: https://oauth.usebruno.com/callback
  authorization_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/auth?prompt=login
  access_token_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/token
  refresh_token_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/token
  client_id: ecommerce-dev
  client_secret: 
  scope: openid profile email
  state: 
  pkce: true
  credentials_placement: body
  credentials_id: customer
  token_placement: header
  token_header_prefix: Bearer
  auto_fetch_token: true
  auto_refresh_token: true
}

script:post-response {
  bru.setVar("accessToken", bru.getOauth2CredentialVar("$oauth2.customer.access_token"));
}

docs {
  Abre a tela de login do Keycloak (realm ecommerce, client público ecommerce-dev com PKCE).
  Faça login com um usuário CUSTOMER — use "Register" na tela do Keycloak para criar um.
  prompt=login força a tela mesmo havendo sessão SSO de outro usuário.

  Salva {{accessToken}}, usado pelas requests de pedido.
  Expected: 200 com sub, email e name do usuário.
}
```

`bruno/keycloak/Login Admin.bru`: igual ao anterior, com `name: Login Admin`, `seq: 2`, `credentials_id: admin`, script `bru.setVar("adminAccessToken", bru.getOauth2CredentialVar("$oauth2.admin.access_token"));` e docs dizendo para logar com `KC_ADMIN_USER_EMAIL`/`KC_ADMIN_USER_PASSWORD` do `.env` e que salva `{{adminAccessToken}}`. Conteúdo completo:

```
meta {
  name: Login Admin
  type: http
  seq: 2
}

get {
  url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/userinfo
  body: none
  auth: oauth2
}

auth:oauth2 {
  grant_type: authorization_code
  callback_url: https://oauth.usebruno.com/callback
  authorization_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/auth?prompt=login
  access_token_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/token
  refresh_token_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/token
  client_id: ecommerce-dev
  client_secret: 
  scope: openid profile email
  state: 
  pkce: true
  credentials_placement: body
  credentials_id: admin
  token_placement: header
  token_header_prefix: Bearer
  auto_fetch_token: true
  auto_refresh_token: true
}

script:post-response {
  bru.setVar("adminAccessToken", bru.getOauth2CredentialVar("$oauth2.admin.access_token"));
}

docs {
  Login com o usuário admin criado pelo realm (KC_ADMIN_USER_EMAIL / KC_ADMIN_USER_PASSWORD do .env).
  Salva {{adminAccessToken}}, usado para criar/alterar produtos e itens de estoque.
  Expected: 200 com o email do admin.
}
```

`bruno/keycloak/Login Second User.bru`:

```
meta {
  name: Login Second User
  type: http
  seq: 3
}

get {
  url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/userinfo
  body: none
  auth: oauth2
}

auth:oauth2 {
  grant_type: authorization_code
  callback_url: https://oauth.usebruno.com/callback
  authorization_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/auth?prompt=login
  access_token_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/token
  refresh_token_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/token
  client_id: ecommerce-dev
  client_secret: 
  scope: openid profile email
  state: 
  pkce: true
  credentials_placement: body
  credentials_id: second
  token_placement: header
  token_header_prefix: Bearer
  auto_fetch_token: true
  auto_refresh_token: true
}

script:post-response {
  bru.setVar("secondUserAccessToken", bru.getOauth2CredentialVar("$oauth2.second.access_token"));
}

docs {
  Login com um SEGUNDO usuário CUSTOMER (diferente do Login Customer) — crie-o com "Register"
  na tela do Keycloak. Salva {{secondUserAccessToken}}, usado em
  order-service-errors/Get Order Forbidden.bru.
}
```

- [ ] **Step 4: Reserve/Release com client_credentials**

Em `bruno/inventory-service/Reserve Stock.bru` e `Release Stock.bru`: troque `auth: none` por `auth: oauth2`, **remova** o bloco `headers { Authorization: ... }` e acrescente:

```
auth:oauth2 {
  grant_type: client_credentials
  access_token_url: {{keycloakUrl}}/realms/ecommerce/protocol/openid-connect/token
  refresh_token_url: 
  client_id: order-service
  client_secret: {{orderServiceClientSecret}}
  scope: 
  credentials_placement: basic_auth_header
  credentials_id: order-service
  token_placement: header
  token_header_prefix: Bearer
  auto_fetch_token: true
  auto_refresh_token: false
}
```

Substitua o parágrafo de docs que fala de `JwtAuthenticationFilter.requiresAdmin` por:

```
  Contrato interno order-service → inventory-service. Exige o token de serviço do client
  order-service (client_credentials, scope inventory:reserve) — por isso esta request usa
  a credencial do order-service. Só funciona nos ambientes Direct: o api-gateway nega
  /api/inventory/reserve e /release (403) para qualquer cliente.
```

- [ ] **Step 5: Atualizar os `docs {}` que ficaram desatualizados**

```bash
grep -rlnE "auth-service|Login\.bru|Register\.bru|JwtAuthenticationFilter|OrderSecurityFilter|email\)|user's email" bruno/
```

Para cada arquivo listado, reescreva só os trechos afetados:
- "auth-service/Register.bru + Login.bru" → "keycloak/Login Customer.bru".
- "auth-service/Login Admin.bru" → "keycloak/Login Admin.bru".
- "Register Second User.bru + Login Second User.bru" → "keycloak/Login Second User.bru".
- "derived from the JWT subject (authentication.getName(), i.e. the logged-in user's email)" → "derived from the token subject (sub, the Keycloak user id — a UUID)".
- Menções a `OrderSecurityFilter`/`JwtAuthenticationFilter` → "Spring Security Resource Server (o serviço valida o token do Keycloak sozinho)".
- Em `*-errors/* No Token.bru` de product e inventory: os Direct agora também respondem `401` sem token (antes não validavam nada).

Rode de novo o `grep` acima. Expected: nenhuma linha.

- [ ] **Step 6: Verificação manual no Bruno**

Abra a collection no Bruno (versão 2.x), selecione o ambiente `Gateway`, execute `keycloak/Login Admin` (abre o navegador do Bruno, faça login), depois `product-service/Create Product`. Expected: `201`. Execute `keycloak/Login Customer` e `order-service/Create Order`. Expected: `201`.

- [ ] **Step 7: Commit**

```bash
git add -A bruno/
git commit -m "chore(bruno): login via Keycloak com PKCE e client_credentials no reserve/release"
```

---

### Task 11: Documentação

**Files:**
- Modify: `README.md`
- Modify: `PROJECT_STATUS.md`
- Modify: `.env.example` (revisão final)

- [ ] **Step 1: README**

Atualize:
1. Lista de serviços: remova o auth-service; adicione **Keycloak** (`http://localhost:8180`, console admin em `/admin` com `admin` / `KEYCLOAK_ADMIN_PASSWORD`) e **Redis**.
2. Diagrama/fluxo: substitua a seção de autenticação pelo texto abaixo.
3. Tabela de endpoints: remova `/auth/register` e `/auth/login`; adicione:

| Método | Rota | Acesso | Descrição |
|---|---|---|---|
| GET | `/oauth2/authorization/keycloak` | Público | Inicia login (navegador) |
| GET | `/bff/me` | Sessão | Dados do usuário logado e cookie CSRF |
| POST | `/logout` | Sessão + CSRF | Encerra sessão no gateway e no Keycloak |

4. Configuração: o `.env` deve partir do `.env.example`; liste as variáveis novas.
5. Rodando pela IDE: variáveis por serviço:

| Serviço | Variáveis |
|---|---|
| api-gateway | `KC_BFF_CLIENT_SECRET`, `REDIS_PASSWORD` |
| order-service | `DB_PASSWORD`, `KC_ORDER_SERVICE_CLIENT_SECRET` |
| inventory-service | `DB_PASSWORD` |
| product-service, notification-service, eureka-server | nenhuma |

(`KEYCLOAK_ISSUER_URI` e `KEYCLOAK_INTERNAL_URL` já têm padrão `localhost:8180`.)

6. Aviso: alterações em `keycloak/realm-ecommerce.json` não se aplicam a um realm já importado (estratégia `IGNORE_EXISTING`); em desenvolvimento, recrie o database `keycloak`:

```bash
docker compose stop keycloak
docker exec postgres psql -U postgres -c 'DROP DATABASE keycloak' -c 'CREATE DATABASE keycloak OWNER keycloak'
docker compose up -d keycloak
```

Texto da seção de autenticação:

```markdown
## Autenticação e autorização

A identidade é delegada ao **Keycloak** (OAuth 2.0 / OpenID Connect), realm `ecommerce`, versionado em `keycloak/realm-ecommerce.json`.

- **Navegador (frontend Angular):** o `api-gateway` atua como **BFF**. O login usa Authorization Code + PKCE; os tokens ficam na sessão do gateway (Redis) e o navegador recebe só o cookie `SESSION` (`HttpOnly`). Escritas exigem o header `X-XSRF-TOKEN`. Em desenvolvimento o `ng serve` deve fazer proxy (`proxy.conf.json`) de `/api`, `/bff`, `/oauth2`, `/login` e `/logout` para `http://localhost:8080`, preservando o `Host`.
- **Clientes de API (Bruno, mobile):** enviam `Authorization: Bearer <token>` obtido no Keycloak (client público `ecommerce-dev`, PKCE).
- **Serviço a serviço:** o `order-service` chama o `inventory-service` com token próprio (client_credentials, scope `inventory:reserve`).
- **Cada serviço valida o token sozinho** (Spring Security Resource Server): assinatura RS256 via JWKS, `iss` e audience `ecommerce-api`. Roles do Keycloak viram `ROLE_*`; scopes viram `SCOPE_*`.

| Serviço | Regra |
|---|---|
| product | GET público; escrita `ADMIN` |
| inventory | GET público; escrita de itens `ADMIN`; `/reserve` e `/release` só com `SCOPE_inventory:reserve` |
| order | autenticado; `CUSTOMER` vê os próprios pedidos (pelo `sub`), `ADMIN` vê todos |
| gateway | nega `/api/inventory/reserve` e `/release` na borda |
```

- [ ] **Step 2: PROJECT_STATUS.md**

- Remova itens que citam o auth-service (ex.: "Testes unitários para regras de Auth").
- Marque como feitos: testes de controller para autenticação/autorização (product, inventory, order, gateway) e testes de integração com Testcontainers (order, realm).
- Adicione ao backlog: SMTP e verificação de email no Keycloak; MFA; login social; tema da tela de login (Keycloakify); OIDC Back-Channel Logout; hardening de produção do Keycloak (`start` com TLS, hostname real); Redis em alta disponibilidade; alinhar versões de Spring Boot/Spring Cloud entre os serviços; implementar o frontend Angular consumindo o BFF.

- [ ] **Step 3: Commit**

```bash
git add README.md PROJECT_STATUS.md .env.example
git commit -m "docs: documenta autenticação com Keycloak, BFF e Resource Servers"
```

---

### Task 12: Verificação ponta a ponta

**Files:** nenhum (só verificação; registre o resultado no PR/descrição do branch).

- [ ] **Step 1: Suíte de testes nova**

```bash
(cd product-service && ./mvnw -q -Dtest='ProductSecurityTest,KeycloakJwtAuthenticationConverterTest' test)
(cd inventory-service && ./mvnw -q -Dtest='InventorySecurityTest,KeycloakJwtAuthenticationConverterTest' test)
(cd order-service && ./mvnw -q -Dtest='OrderSecurityTest,OrderServiceOwnershipTest,OrderServiceReserveErrorTest,OrderServiceToInventoryAuthIntegrationTest,KeycloakJwtAuthenticationConverterTest' test)
(cd api-gateway && ./mvnw -q -Dtest='BearerChainTest,SessionChainTest,SessionTokenRelayFilterTest,KeycloakRealmIntegrationTest' test)
```

Expected: tudo PASS.

- [ ] **Step 2: Acesso direto sem gateway é bloqueado (pela IDE, com as portas locais)**

Com os serviços rodando pela IDE:

```bash
curl -s -o /dev/null -w "product POST sem token: %{http_code}\n" -X POST localhost:8082/api/product -H 'Content-Type: application/json' -d '{}'
curl -s -o /dev/null -w "inventory reserve sem token: %{http_code}\n" -X POST localhost:8083/api/inventory/reserve -H 'Content-Type: application/json' -d '{"items":[{"skuCode":"x","quantity":1}]}'
curl -s -o /dev/null -w "order GET sem token: %{http_code}\n" localhost:8081/api/order
```

Expected: `401` nos três.

- [ ] **Step 3: Token de usuário não reserva estoque**

```bash
set -a; . ./.env; set +a
TOKEN=$(keycloak/scripts/pkce-login.sh http://localhost:8180 ecommerce-dev - "$KC_ADMIN_USER_EMAIL" "$KC_ADMIN_USER_PASSWORD" https://oauth.usebruno.com/callback | sed -n 's/^ACCESS_TOKEN //p')
curl -s -o /dev/null -w "reserve com token de ADMIN: %{http_code}\n" -X POST localhost:8083/api/inventory/reserve \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"items":[{"skuCode":"x","quantity":1}]}'
```

Expected: `403`.

- [ ] **Step 4: Fluxo BFF no navegador**

1. Abra `http://localhost:8080/oauth2/authorization/keycloak`, faça login com o admin. Expected: redireciona para `http://localhost:4200/` (sem frontend, a página não carrega — tudo bem).
2. Abra `http://localhost:8080/bff/me`. Expected: JSON com `sub`, `name`, `email`, `roles` contendo `ADMIN`.
3. Abra `http://localhost:8080/api/order`. Expected: `200` (lista).
4. DevTools → Application → Cookies: `SESSION` com `HttpOnly`; `XSRF-TOKEN` sem `HttpOnly`; nenhum cookie contém token JWT.
5. **Restart:** reinicie o api-gateway pela IDE e recarregue `http://localhost:8080/api/order`. Expected: `200` sem novo login (Review Focus 1).
6. **Logout:** no console do navegador:
   ```js
   const f = document.createElement('form'); f.method = 'POST'; f.action = '/logout';
   const i = document.createElement('input'); i.type = 'hidden'; i.name = '_csrf';
   i.value = decodeURIComponent(document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')).split('=')[1]);
   f.appendChild(i); document.body.appendChild(f); f.submit();
   ```
   Expected: passa pelo logout do Keycloak e volta para `http://localhost:4200/`; `http://localhost:8080/bff/me` passa a responder `401`.

- [ ] **Step 5: Keycloak fora do ar**

```bash
docker compose stop keycloak
```

Crie um pedido pelo Bruno (token já obtido). Expected: `503` com ProblemDetail "Serviço de autenticação indisponível" se o token de serviço do order-service tiver expirado/não existir; `201` se ainda estiver em cache (até 5 min). Depois: `docker compose start keycloak`.

- [ ] **Step 6: Fechamento**

Use a skill `superpowers:finishing-a-development-branch` para decidir merge/PR do branch `feat/keycloak-bff`.
