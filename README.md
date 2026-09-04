# E-commerce baseado em microsserviços

Backend de e-commerce desenvolvido como projeto de TCC para demonstrar arquitetura distribuída com Spring Boot, Spring Cloud, autenticação JWT, comunicação síncrona e assíncrona e observabilidade.

> O projeto está em desenvolvimento. A infraestrutura e os fluxos principais existem, mas o fluxo de compra ainda precisa de correções de integridade, segurança e testes antes de ser considerado concluído. Consulte [PROJECT_STATUS.md](PROJECT_STATUS.md) para o diagnóstico completo.

## Arquitetura atual

| Componente | Porta | Persistência | Responsabilidade | Estado |
|---|---:|---|---|---|
| API Gateway | 8080 | — | Roteamento, validação JWT e autorização por perfil | Funcional |
| Eureka Server | 8761 | — | Descoberta e registro de serviços | Funcional |
| Auth Service | 8086 | PostgreSQL | Cadastro, login, BCrypt, JWT e perfis | Funcional, requer endurecimento |
| Product Service | 8082 | MongoDB | Catálogo e CRUD de produtos | Funcional |
| Inventory Service | 8083 | PostgreSQL | Consulta e manutenção de estoque | Parcial: não realiza reserva/baixa |
| Order Service | 8081 | PostgreSQL | Criação e consulta de pedidos | Parcial: não baixa estoque |
| Notification Service | 8084 | — | Consumo do evento de pedido criado | Parcial: registra apenas no log |

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

Todas as chamadas externas devem passar pelo gateway em `http://localhost:8080`.

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
- Portas `3000`, `5432`, `5672`, `8080`–`8086`, `8761`, `9090`, `9411`, `15672` e `27017` disponíveis

### Configuração

Crie um `.env` na raiz (há um arquivo local ignorado pelo Git) contendo:

```dotenv
DB_PASSWORD=defina-uma-senha
JWT_SECRET=defina-uma-chave-com-pelo-menos-32-caracteres
```

### Inicialização

```bash
docker compose up --build
```

Serviços úteis:

- Gateway: `http://localhost:8080`
- Eureka: `http://localhost:8761`
- RabbitMQ Management: `http://localhost:15672` (`guest`/`guest`)
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin`/`admin`)
- Zipkin: `http://localhost:9411`

O script `init-db.sql` só é executado quando o volume do PostgreSQL é criado. Em um volume antigo, os bancos adicionados posteriormente precisam ser criados manualmente ou o ambiente deve ser recriado conscientemente.

### Encerramento

```bash
docker compose down
```

Não use `docker compose down -v` se quiser preservar os bancos locais.

## Testes

Cada módulo possui Maven Wrapper:

```bash
cd auth-service
./mvnw test
```

Atualmente existe apenas um teste `contextLoads()` por módulo. Eles passaram na última auditoria, mas não cobrem regras de negócio, endpoints ou o fluxo ponta a ponta e podem usar infraestrutura local. A criação de testes isolados está entre as prioridades do projeto.

## Escopo não implementado

- Não há frontend neste repositório.
- Não existe Payment Service, apesar de versões antigas da documentação o mencionarem.
- O `ai-service` foi planejado, mas ainda não foi criado.

O escopo recomendado para a primeira versão concluída é estabilizar os sete módulos atuais antes de adicionar IA, pagamento ou interface gráfica.
