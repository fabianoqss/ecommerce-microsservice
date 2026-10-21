# E-commerce baseado em microsserviços

Backend de e-commerce com Spring Boot, Spring Cloud, identidade OIDC via Keycloak, comunicação síncrona e assíncrona e observabilidade.

O fluxo principal inclui estoque atômico, cancelamento de pedidos, Outbox, DLQ e autorização por dono do pedido. O gateway oferece login pelo navegador como BFF e acesso à API com Bearer token. Consulte [PROJECT_STATUS.md](PROJECT_STATUS.md) para as pendências e [docs/PLANO_EXECUCAO.md](docs/PLANO_EXECUCAO.md) para o histórico do P0.

## Arquitetura atual

| Componente | Porta no container | Persistência | Responsabilidade |
|---|---:|---|---|
| API Gateway | 8080; gerenciamento 8090 | Redis | BFF OIDC, sessão, token relay e autorização de borda |
| Eureka Server | 8761 | — | Descoberta de serviços protegida por HTTP Basic |
| Product Service | 8082 | MongoDB | Catálogo e CRUD de produtos |
| Inventory Service | 8083 | PostgreSQL | Consulta, manutenção e reserva atômica de estoque |
| Order Service | 8081 | PostgreSQL | Pedidos, cancelamento, compensação e Outbox |
| Notification Service | 8084 | Redis | Consumo idempotente de eventos, retry e DLQ; notificação no log |
| Keycloak | 8080; gerenciamento 9000 | PostgreSQL | Identidade, cadastro, login, recuperação de senha e emissão de tokens |

Os seis módulos Java são gateway, Eureka, Product, Inventory, Order e Notification. Os serviços de negócio validam assinatura, issuer e audience dos tokens localmente. A identidade do pedido é o `sub` do Keycloak, e as roles de usuário são `CUSTOMER` e `ADMIN`.

```text
Navegador -> Gateway BFF <-> Keycloak -> PostgreSQL
                 |-> Redis (sessões)
Cliente Bearer -> Gateway
                    |-> Product -> MongoDB
                    |-> Inventory -> PostgreSQL
                    `-> Order -> PostgreSQL (pedido + Outbox)
                          |-> Product/Inventory via OpenFeign
                          `-> RabbitMQ -> Notification -> Redis (idempotência)
                                       `-> Inventory -> PostgreSQL (liberação idempotente no cancelamento)

Eureka: descoberta interna com HTTP Basic
Prometheus/Grafana: métricas; Zipkin: traces
```

## Tecnologias

- Java 21, Spring Boot 4.0.x e Spring Cloud 2025.1.x
- Spring MVC, Data JPA, Data MongoDB e Flyway
- Spring Cloud Gateway Server Web MVC, Eureka e OpenFeign
- Spring Security OAuth2/OIDC Resource Server e Client; Authorization Code com PKCE
- PostgreSQL, MongoDB, RabbitMQ e Redis
- Docker Compose, Actuator, Prometheus, Grafana, Zipkin e Springdoc OpenAPI

## Endpoints

As chamadas externas à aplicação passam por `http://localhost:8080`. No Compose, as portas de Product, Inventory, Order, Notification, Eureka e do Actuator do gateway não são publicadas no host.

### Login pelo navegador

| Método | Rota no gateway | Descrição |
|---|---|---|
| GET | `/oauth2/authorization/keycloak` | Inicia Authorization Code com PKCE no Keycloak |
| GET | `/login/oauth2/code/keycloak` | Callback OIDC do gateway |
| GET | `/bff/me` | Dados do usuário autenticado por sessão |
| POST | `/logout` | Encerra a sessão e inicia logout OIDC |

O BFF mantém os tokens no servidor e usa sessão no Redis. Requisições que alteram estado com autenticação por sessão, inclusive logout, exigem proteção CSRF (`XSRF-TOKEN` e header `X-XSRF-TOKEN`). O acesso por Bearer token é stateless. Cadastro e recuperação de senha acontecem no Keycloak, conforme a configuração do realm.

### API de negócio

| Método | Rota | Acesso |
|---|---|---|
| GET | `/api/product`, `/api/product/{id}` | Público |
| POST | `/api/product` | `ROLE_ADMIN` |
| PUT, DELETE | `/api/product/{id}` | `ROLE_ADMIN` |
| GET | `/api/inventory?skuCode=id` | Público |
| POST | `/api/inventory` | `ROLE_ADMIN` |
| PUT | `/api/inventory/{id}` | `ROLE_ADMIN` |
| GET | `/api/order` | Autenticado; usuário vê seus pedidos, admin vê todos |
| GET | `/api/order/{id}` | Dono ou admin |
| POST | `/api/order` | Autenticado |
| POST | `/api/order/{id}/cancel` | Dono ou admin; cancelamento idempotente de pedido confirmado |

`PUT`, `DELETE` e `GET /api/inventory/reservations/{reservationId}` são contratos internos do livro-razão de reservas, bloqueados no gateway (`denyAll`). Na criação do pedido e na reconciliação de pendentes expirados, o Order obtém um token com `client_credentials` e scope `inventory:reserve` para chamá-los diretamente no Inventory. O cancelamento de pedido confirmado grava o evento `ORDER_CANCELLED` (v3) no Outbox e o Inventory libera o estoque de forma assíncrona e idempotente via RabbitMQ (`inventory.order.cancelled.queue`).

## Execução local

### Pré-requisitos

- Docker com Docker Compose v2; Java 21 para executar pela IDE ou Maven Wrapper
- OpenSSL para gerar os segredos locais
- Portas `3000`, `5432`, `5672`, `6379`, `8025`, `8080`, `8180`, `9090`, `9411`, `15672` e `27017` disponíveis

### Build com Maven

Importe o `pom.xml` da raiz como projeto Maven com JDK 21. O parent agrega os seis serviços e o JAR `common-security`, que concentra apenas a conversão de autoridades Keycloak. O Maven Wrapper é único na raiz (Maven 3.9.12); no Windows, use `mvnw.cmd`.

Execute a partir da raiz:

```bash
# Compila e verifica todos os módulos
./mvnw verify

# Verifica um serviço e os módulos dos quais ele depende
./mvnw -pl order-service -am verify

# Executa apenas os testes do conversor compartilhado
./mvnw -pl common-security test
```

Os testes de integração com Testcontainers precisam de Docker acessível. A primeira execução do wrapper e a resolução de dependências precisam de acesso ao Maven Central. O Compose usa a raiz como contexto de build e o Dockerfile de cada serviço; a imagem final contém somente o runtime Java e o curl necessário aos healthchecks.

### Arquivos e profiles do Compose

O Compose da raiz é o ponto de entrada oficial de toda a infraestrutura.

| Arquivo | Conteúdo |
|---|---|
| `docker-compose.yml` | Produção: aplicações, Keycloak, RabbitMQ, Redis e monitoramento; bancos externos na OCI |
| `docker-compose.dev.yml` | Override local: PostgreSQL, MongoDB, Mailpit e ajustes de rede/configuração para desenvolvimento |

| Profile | Serviços |
|---|---|
| _(nenhum)_ | `keycloak`, `rabbitmq`, `rabbitmq-init`, `redis`; em dev também `postgres`, `mongo`, `mailpit` |
| `monitoring` | `zipkin`, `prometheus`, `grafana` |
| `apps` | `eureka-server`, `api-gateway`, `product-service`, `inventory-service`, `order-service`, `notification-service` |

O `.env.example` da raiz é a referência oficial e define `COMPOSE_PROFILES=monitoring,apps`. Endereços e segredos são configuráveis por ambiente; variáveis obrigatórias usam `${VAR:?}` para o Compose rejeitar configuração incompleta.

### Configuração do ambiente

Na raiz do repositório:

```bash
./scripts/generate-env.sh
```

O script cria `.env` a partir do exemplo e substitui **cada** valor `troque-...` por um segredo independente de 24 bytes gerado por `openssl rand -hex 24`. Não imprime os segredos e cria o arquivo com permissão `600`. Recusa sobrescrever um `.env` existente; `./scripts/generate-env.sh --force` cria um backup com permissão `600` antes da substituição. O `.env` e seus backups contêm credenciais e não devem ser compartilhados ou versionados.

Em desenvolvimento, Order e Inventory usam o usuário `postgres` do container local: a senha efetiva é `DB_PASSWORD`. O exemplo define `ORDER_DB_PASSWORD=${DB_PASSWORD}` e `INVENTORY_DB_PASSWORD=${DB_PASSWORD}`, referências expandidas pelo Compose. Em produção, configure usuários e senhas próprios para cada banco e gere as variáveis pelo OCI Vault. O banco do Keycloak tem usuário e senha separados.

O Eureka exige `EUREKA_USERNAME` e `EUREKA_PASSWORD`, sem senha padrão. Os clientes incluem essas credenciais na URL de registro. Credenciais definidas manualmente precisam usar caracteres seguros para URL (`A–Z`, `a–z`, `0–9`, `_`, `.`, `~` e `-`); prefira a senha hexadecimal gerada pelo script.

### Realm do Keycloak

O realm `ecommerce` ainda é configurado manualmente pelo console. As variáveis de client secret precisam corresponder aos clients criados no realm; definir uma variável no `.env` não cria o client nem o usuário no Keycloak.

Configure as roles `CUSTOMER` e `ADMIN`, a audience `ecommerce-api` e estes clients:

| Client | Configuração |
|---|---|
| `ecommerce-bff` | Confidencial; Authorization Code e PKCE S256; callback local `http://localhost:8080/login/oauth2/code/keycloak`; secret `KC_BFF_CLIENT_SECRET` |
| `ecommerce-dev` | Público; Authorization Code e PKCE S256; callback `https://oauth.usebruno.com/callback`; exclusivo de desenvolvimento |
| `order-service` | Confidencial; service account e `client_credentials`; scope `inventory:reserve`; secret `KC_ORDER_SERVICE_CLIENT_SECRET` |

Garanta que os access tokens tenham `aud=ecommerce-api` e as roles/scopes necessários. No ambiente local, o issuer público é `http://localhost:8180/realms/ecommerce`; containers consultam JWKs e token endpoint pela rede interna. As variáveis `OIDC_*` controlam esses endereços. Configure SMTP para `mailpit:1025` em desenvolvimento para inspecionar e-mails sem entrega externa.

Em produção, publique Keycloak e gateway por HTTPS atrás do nginx, configure `KEYCLOAK_HOSTNAME` e os redirects públicos, e mantenha `SESSION_COOKIE_SECURE=true` (padrão). O override de desenvolvimento usa `false` para HTTP local.

### Inicialização

```bash
# Desenvolvimento local: infraestrutura e aplicações
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# Produção: ambiente gerado a partir do Vault
docker compose --env-file <arquivo-de-ambiente> up -d --build
```

`rabbitmq-init` é um serviço de execução única: após o broker ficar saudável, `scripts/rabbitmq-init.sh` usa a API HTTP para provisionar o vhost `ecommerce` e os usuários `order-service`, `notification-service` e `inventory-service`. A saída normal desse container é terminar com código `0`; não é um processo permanente.

As aplicações usam `RABBITMQ_ORDER_PASSWORD`, `RABBITMQ_NOTIFICATION_PASSWORD` e `RABBITMQ_INVENTORY_PASSWORD` no vhost `ecommerce` com privilégio mínimo: `order-service` (único dono da topologia e produtor) tem `configure`, `write` e `read` em `^(order|inventory)\.`, `notification-service` (consumidor) tem `configure` e `write` em `^$` e `read` apenas em `^order\.(created|cancelled)\.queue$`, e `inventory-service` (consumidor do cancelamento) tem `configure` e `write` em `^$` e `read` apenas em `^inventory\.order\.cancelled\.queue$`. `RABBITMQ_ADMIN_USERNAME`/`RABBITMQ_ADMIN_PASSWORD` são credenciais administrativas para o console e o provisionamento, não para os microsserviços. AMQP (`5672`) só é publicado no override local, em loopback.

Serviços úteis (publicados em `127.0.0.1`; na VM, acesse por túnel SSH):

- Gateway: `http://localhost:8080`
- Keycloak: `http://localhost:8180` (`admin` / `KEYCLOAK_ADMIN_PASSWORD`)
- RabbitMQ Management: `http://localhost:15672` (`RABBITMQ_ADMIN_USERNAME` / `RABBITMQ_ADMIN_PASSWORD`)
- Mailpit, somente dev: `http://localhost:8025`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin` / `GRAFANA_ADMIN_PASSWORD`)
- Zipkin: `http://localhost:9411`

Eureka só fica acessível na rede interna do Compose, em `http://eureka-server:8761`, com HTTP Basic. O Actuator do gateway usa a porta interna `8090`, sem publicação no host. O Prometheus coleta as métricas pela rede interna. A amostragem de traces é `1.0` em dev e, por padrão, `0.1` em produção.

### Execução pela IDE

Remova `COMPOSE_PROFILES` do `.env` para subir só a infraestrutura; para manter o monitoramento, use `COMPOSE_PROFILES=monitoring`. Execute o mesmo comando Compose de desenvolvimento. Importe o `pom.xml` da raiz na IDE, aguarde a sincronização Maven e configure o JDK 21. Crie uma configuração Spring Boot para a classe principal anotada com `@SpringBootApplication` de cada serviço, usando o classpath do respectivo módulo; `common-security` é uma biblioteca, sem aplicação própria. Use o diretório do serviço como diretório de trabalho e configure as variáveis emitidas pelo script:

```bash
./scripts/ide-env.sh
```

O script lê o `.env` da raiz e emite as variáveis para Eureka, Product, Inventory, Order, Notification e Gateway, incluindo os endereços locais necessários. Copie cada bloco para a configuração do módulo correspondente na IDE. **A saída contém segredos: execute apenas no seu terminal e não cole em chats, logs ou arquivos versionados.**

### Dados existentes e upgrades

O `init-db.sh` só roda quando o volume local do PostgreSQL é criado. Em volumes antigos, crie manualmente os bancos/usuários que faltam. Alterar a senha no `.env` também não altera automaticamente as credenciais já persistidas nos bancos ou no Keycloak; coordene a rotação.

O Compose fixa PostgreSQL `16.12`, MongoDB `7.0.45`, RabbitMQ `4.2.5-management`, Keycloak `26.7.5` e Mailpit `v1.31.5`. Consulte as [notas oficiais do MongoDB 7.0](https://www.mongodb.com/docs/v7.0/release-notes/7.0/) e a [release do Mailpit](https://github.com/axllent/mailpit/releases/tag/v1.31.5) para as correções incluídas.

**RabbitMQ 3.x → 4.x:** não reutilize diretamente um volume antigo sem preparar o upgrade. Para preservar mensagens, siga a [trilha de upgrade suportada](https://www.rabbitmq.com/docs/upgrade) e habilite as feature flags estáveis exigidas ainda na versão de origem antes de avançar. Em desenvolvimento, se puder perder as mensagens e definições locais, pare os serviços, identifique o volume deste projeto correspondente a `rabbitmq-data` e recrie **somente esse volume**. O nome Docker inclui o prefixo do projeto. O provisionamento recriará o vhost e os usuários; bancos e os outros volumes devem ser preservados.

**Keycloak 26.4.7 → 26.7.5:** a versão anterior é afetada pelos avisos [GHSA-95cx-vmr5-3cmr](https://github.com/keycloak/keycloak/security/advisories/GHSA-95cx-vmr5-3cmr) e [GHSA-j97h-3f8r-mrjr](https://github.com/keycloak/keycloak/security/advisories/GHSA-j97h-3f8r-mrjr). O segundo lista correção em `26.6.4`, sem patch listado para a linha `26.4`. Antes de atualizar um banco existente, faça backup e revise as mudanças de migração `26.5`, `26.6` e `26.7`, até `26.7.5`, no [guia oficial de upgrade](https://www.keycloak.org/docs/latest/upgrading/index.html). Valide realm, clients, redirects, login e emissão de tokens após a atualização.

### Encerramento

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```

O `down` respeita os profiles ativos no ambiente. Não use `down -v` se quiser preservar os dados locais.

## Testes

Use o Maven Wrapper único, a partir da raiz:

```bash
./mvnw -pl order-service -am test
./mvnw -pl api-gateway -am test
./mvnw -o -q -pl eureka-server -am test
python3 -m unittest discover -s scripts/tests -v
```

Há testes unitários, de autorização MVC, de sessão/Bearer no gateway e de integração do fluxo Order/Inventory. Os testes que usam Testcontainers precisam de Docker acessível. A cobertura ainda deve ser ampliada, especialmente para eventos e o fluxo ponta a ponta; veja o backlog.

No Bruno, abra a coleção `bruno/` e selecione `Gateway` para as chamadas externas. O login de usuário usa Authorization Code com PKCE no client público `ecommerce-dev`. As rotas internas de reserva (`/api/inventory/reservations/{reservationId}`) usam `client_credentials` do `order-service` **apenas no ambiente Direct**, com Inventory executado localmente pela IDE; essas rotas permanecem bloqueadas no gateway. Informe client secrets nas variáveis **secret** do Bruno, sem versioná-los.

## Escopo

Não há frontend, Payment Service ou AI Service neste repositório. A prioridade é estabilizar e ampliar a validação dos seis módulos Java atuais antes de adicionar novos serviços ou interface gráfica.
