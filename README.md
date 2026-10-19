# E-commerce baseado em microsserviços

Backend de e-commerce que demonstra arquitetura distribuída com Spring Boot, Spring Cloud, autenticação JWT, comunicação síncrona e assíncrona e observabilidade.

> As sete fases de fechamento do P0 foram implementadas. O fluxo principal está funcional, com estoque atômico, Outbox, DLQ, idempotência e acesso externo somente pelo gateway. A principal pendência restante é ampliar os testes automatizados. Consulte [PROJECT_STATUS.md](PROJECT_STATUS.md) para o diagnóstico completo.

## Arquitetura atual

| Componente | Porta | Persistência | Responsabilidade | Estado |
|---|---:|---|---|---|
| API Gateway | 8080 | — | Roteamento, validação JWT e autorização por perfil | Funcional |
| Eureka Server | 8761 (interna) | — | Descoberta e registro de serviços | Funcional |
| Auth Service | 8086 | PostgreSQL | Cadastro, login, BCrypt, JWT e perfis | Funcional, requer endurecimento |
| Product Service | 8082 | MongoDB | Catálogo e CRUD de produtos | Funcional |
| Inventory Service | 8083 (interna) | PostgreSQL | Consulta, manutenção e reserva atômica de estoque | Funcional |
| Order Service | 8081 (interna) | PostgreSQL | Criação, consulta e Outbox de pedidos | Funcional |
| Notification Service | 8084 (interna) | — | Consumo idempotente, retry e DLQ do evento de pedido | Funcional; notificação registrada no log |

Infraestrutura: PostgreSQL 16, MongoDB 7, RabbitMQ, Zipkin, Prometheus e Grafana.

```text
Cliente
  -> API Gateway
      -> Auth Service -> PostgreSQL
      -> Product Service -> MongoDB
      -> Inventory Service -> PostgreSQL
      -> Order Service -> PostgreSQL
            |-> Product/Inventory via OpenFeign
            `-> RabbitMQ -> Notification Service
```

## Tecnologias

- Java 21
- Spring Boot 4.0.x
- Spring Cloud 2025.1.x
- Spring MVC, Data JPA e Data MongoDB
- Spring Cloud Gateway Server Web MVC
- Eureka, OpenFeign e RabbitMQ
- PostgreSQL, MongoDB e Flyway
- JWT Auth0 e BCrypt
- Docker e Docker Compose
- Actuator, Prometheus, Grafana e Zipkin
- Springdoc OpenAPI/Swagger

## Endpoints

Todas as chamadas externas devem passar pelo gateway em `http://localhost:8080`. As portas dos microsserviços e do Eureka não são publicadas no host.

### Autenticação

| Método | Rota | Acesso | Descrição |
|---|---|---|---|
| POST | `/auth/register` | Público | Cria usuário com `ROLE_USER` |
| POST | `/auth/login` | Público | Retorna token JWT e dados do usuário |

### Produtos

| Método | Rota | Acesso |
|---|---|---|
| GET | `/api/product` | Público |
| GET | `/api/product/{id}` | Público |
| POST | `/api/product` | `ROLE_ADMIN` |
| PUT | `/api/product/{id}` | `ROLE_ADMIN` |
| DELETE | `/api/product/{id}` | `ROLE_ADMIN` |

### Estoque

| Método | Rota | Acesso |
|---|---|---|
| GET | `/api/inventory?skuCode=id` | Público |
| POST | `/api/inventory` | `ROLE_ADMIN` |
| PUT | `/api/inventory/{id}` | `ROLE_ADMIN` |

### Pedidos

| Método | Rota | Acesso |
|---|---|---|
| GET | `/api/order` | Autenticado |
| GET | `/api/order/{id}` | Autenticado |
| POST | `/api/order` | Autenticado |

## Execução local

### Pré-requisitos

- Docker Desktop com Docker Compose
- Portas `3000`, `5432`, `5672`, `6379`, `8080`, `9090`, `9411`, `15672` e `27017` disponíveis

### Arquivos do Compose

| Arquivo                  | Conteúdo                                                                                              |
| ------------------------ | ----------------------------------------------------------------------------------------------------- |
| `docker-compose.yml`     | Produção: microsserviços, Keycloak, RabbitMQ, Redis e monitoramento. Os bancos ficam na OCI           |
| `docker-compose.dev.yml` | Override só de desenvolvimento: adiciona `postgres` e `mongo` em containers                           |

Todo endereço e segredo entra por variável de ambiente. As obrigatórias usam `${VAR:?}`, então o Compose falha antes de subir se faltar alguma.

### Configuração

Copie o `.env.example` para `.env` (ignorado pelo Git) e troque os valores. Em produção, o mesmo conjunto de variáveis é gerado a partir do OCI Vault, apontando para os serviços da OCI.

A autenticação usa o Keycloak, com o realm `ecommerce` configurado pelo console de administração (a migração dos serviços está em andamento; ver `docs/superpowers/specs/2026-10-13-keycloak-bff-plan.md`). O gateway só conhece OIDC genérico (`OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI`, `OIDC_AUDIENCE`), então trocar o provedor (Cognito, Entra ID, OCI IAM) é só configuração. Em produção, o Keycloak fica atrás do nginx (`KEYCLOAK_HOSTNAME` é a URL pública) e o banco dele fica no PostgreSQL da OCI.

### Inicialização

Os serviços estão separados em profiles:

| Profile      | Serviços                                                                                            |
| ------------ | --------------------------------------------------------------------------------------------------- |
| _(nenhum)_   | `keycloak`, `rabbitmq`, `redis` e, em dev, `postgres` e `mongo` (sobem sempre)                      |
| `monitoring` | `zipkin`, `prometheus`, `grafana`                                                                   |
| `apps`       | `eureka-server`, `api-gateway`, `auth-service`, `product-service`, `inventory-service`, `order-service`, `notification-service` |

O `.env.example` já define `COMPOSE_PROFILES=monitoring,apps`. Remova essa linha do `.env` para subir só a infraestrutura e rodar os serviços pela IDE.

```bash
# Desenvolvimento local
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# Produção (na VM, com o arquivo de ambiente gerado a partir do Vault)
docker compose --env-file <arquivo-de-ambiente> up -d --build
```

Serviços úteis (publicados só em `127.0.0.1`; na VM, acesse por túnel SSH):

- Gateway: `http://localhost:8080`
- Keycloak: `http://localhost:8180` (`admin`/`KEYCLOAK_ADMIN_PASSWORD`)
- Eureka: disponível apenas na rede interna do Compose, em `http://eureka-server:8761`
- RabbitMQ Management: `http://localhost:15672` (`RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD`)
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin`/`GRAFANA_ADMIN_PASSWORD`)
- Zipkin: `http://localhost:9411`

O script `init-db.sh` só é executado quando o volume do PostgreSQL local é criado. Em um volume antigo, os bancos adicionados posteriormente precisam ser criados manualmente ou o ambiente deve ser recriado conscientemente.

### Encerramento

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```

O `down` respeita o `COMPOSE_PROFILES` do `.env`; sem ele, só remove os serviços sem profile.

Não use `docker compose down -v` se quiser preservar os bancos locais.

## Testes

Cada módulo possui Maven Wrapper:

```bash
cd auth-service
./mvnw test
```

Atualmente existe apenas um teste `contextLoads()` por módulo. Eles não cobrem regras de negócio ou endpoints e alguns ainda dependem de configuração local. A criação de testes isolados continua entre as prioridades do projeto. As Fases 6 e 7 foram validadas manualmente pelo gateway, inclusive com indisponibilidade temporária do RabbitMQ, entrega posterior via Outbox, DLQ e rejeição de evento duplicado.

## Escopo não implementado

- Não há frontend neste repositório.
- Não existe Payment Service, apesar de versões antigas da documentação o mencionarem.
- O `ai-service` foi planejado, mas ainda não foi criado.

O escopo recomendado para a primeira versão concluída é estabilizar os sete módulos atuais antes de adicionar IA, pagamento ou interface gráfica.
