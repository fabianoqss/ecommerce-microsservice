# CLAUDE.md — TCC Microsserviços E-commerce

## Visão Geral
TCC comparando arquitetura de microsserviços vs monolítico com Spring Boot + Spring Cloud.
4 serviços independentes registrados no Eureka, comunicação via OpenFeign.

## Estrutura de Pastas
```
microsservice-ecommerce/
├── eureka-server/         porta 8761
├── product-service/       porta 8082 | MongoDB
├── order-service/         porta 8081 | PostgreSQL
├── inventory-service/     porta 8083 | PostgreSQL
```

## Ordem de inicialização
1. eureka-server (obrigatório primeiro)
2. product-service, inventory-service (qualquer ordem)
3. order-service (depende dos outros dois via Feign)

## Bancos de dados
| Serviço           | Banco      | Database          | Schema via       |
|-------------------|------------|-------------------|------------------|
| product-service   | MongoDB    | product_db        | automático       |
| order-service     | PostgreSQL | order-service     | JPA ddl-auto=update |
| inventory-service | PostgreSQL | inventory_service | Flyway           |

Senhas via variáveis de ambiente: `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`
Arquivos .env estão no .gitignore.

## Comunicação entre serviços
- order-service → product-service: `ProductClient` (@FeignClient name="product-service") → GET /api/product/{id}
- order-service → inventory-service: `InventoryClient` (@FeignClient name="inventory-service") → GET /api/inventory?skuCode=...
- Resilience4j está no pom do order-service mas ainda NÃO configurado

## Endpoints implementados

### product-service (/api/product)
- GET    /api/product          → listar todos
- GET    /api/product/{id}     → buscar por id (lança ResourceNotFoundException se não encontrar)
- POST   /api/product          → criar (retorna 201 + Location header)
- PUT    /api/product/{id}     → atualizar
- DELETE /api/product/{id}     → deletar (retorna 204)

### inventory-service (/api/inventory)
- GET /api/inventory?skuCode=x&skuCode=y  → verifica estoque (retorna List<InventoryDTO> com inStock)
- POST /api/inventory                      → inserir item (retorna 201)
- PUT  /api/inventory/{id}                → atualizar quantidade

### order-service (/api/order)
- POST /api/order  → createOrder() (verifica estoque, busca produto, calcula total, salva)
- GET  /api/order  → PENDENTE
- GET  /api/order/{id} → PENDENTE

## Convenções do projeto
- DTOs: records Java (exceto inventory-service que usa classe com getters manuais)
- Entidades: getters/setters manuais (sem Lombok) no product-service e inventory-service
- Entidades: Lombok no order-service (@Getter @Setter @NoArgsConstructor @AllArgsConstructor)
- Exceções customizadas: ResourceNotFoundException extends RuntimeException
- Pacote de exceções: `services.exceptions` (product-service)
- Pacote de handlers: `controllers.handlers` (product-service)
- Feign clients: pacote `client/`

## groupIds
- product-service: `com.ecommerce`
- order-service, inventory-service, eureka-server: `com.example.ecommerce`

## Infraestrutura local
- MongoDB: docker-compose em `product-service/infra/` (porta 27017, container "mongo-product")
- PostgreSQL: deve estar rodando localmente na porta 5432
- Flyway em inventory-service: migrations em `resources/db/migration/`, baseline-on-migrate=true

## O que esta PENDENTE (Fase 1)
1. `ControllerExceptionHandler` no product-service — classe existe mas está VAZIA, falta @RestControllerAdvice
   - Tratar ResourceNotFoundException → 404
   - Tratar MethodArgumentNotValidException → 400 com detalhes
2. GET /api/order e GET /api/order/{id} no order-service
3. Circuit breaker (Resilience4j) nos FeignClients do order-service

## Proximas fases planejadas
- Fase 1.5: notification-service (porta 8084) + RabbitMQ
  - order-service publica evento "OrderCreated" no RabbitMQ
  - notification-service consome o evento e notifica (log/e-mail)
  - Demonstra comunicação ASSINCRONA (complementa o Feign sincrono)
- Fase 2: API Gateway (porta 8080) + Keycloak (porta 8180)
- Fase 3: Monitoramento — Actuator + Prometheus + Grafana + Zipkin
- Fase 4: Sistema Monolítico (mesmo domínio, arquitetura única)
- Fase 5: ai-service (Python + FastAPI + Claude API + MCP tools)
- Fase 6: Deploy Azure + Testes de carga com k6
