# 🛒 E-commerce baseado em Microsserviços

Este projeto consiste em uma **plataforma de e-commerce desenvolvida com arquitetura de microsserviços**, com foco em **sistemas distribuídos**, **comunicação orientada a eventos** e **execução em ambiente de nuvem**.

O objetivo principal do projeto é servir como **base prática para um Trabalho de Conclusão de Curso (TCC)**, explorando tecnologias modernas amplamente utilizadas no mercado.

---

## 🧩 Arquitetura Geral

O sistema é composto por microsserviços independentes, cada um responsável por um contexto específico do domínio do e-commerce:

- **Order Service** → gerenciamento de pedidos  
- **Product Service** → catálogo de produtos  
- **Inventory Service** → controle de estoque  
- **Payment Service** → processamento de pagamentos (simulado)  
- **Notification / Aggregator Service** → consolidação de eventos e notificações  

A comunicação entre os serviços é realizada, sempre que possível, de forma **assíncrona**, utilizando **eventos**, visando baixo acoplamento e maior escalabilidade.

---

## 🏗️ Estrutura do Repositório

```text
ecommerce-microservices/
 ├── services/
 │    ├── order-service/
 │    ├── product-service/
 │    ├── inventory-service/
 │    ├── payment-service/
 │    └── notification-service/
 │
 ├── infra/
 │    ├── docker-compose.yml
 │    ├── prometheus/
 │    └── grafana/
 │
 ├── docs/
 │    ├── diagrams/
 │    └── api/
 │
 └── README.md

```
## 🛠️ Tecnologias Utilizadas

- Java 17+

- Spring Boot

- Spring Web

- Spring Data JPA

- Spring Data MongoDB

- Spring Boot Actuator

- Bancos de Dados

- PostgreSQL (Order, Payment)

- MongoDB (Product)

- Infraestrutura e Observabilidade

- Docker / Docker Compose

- Prometheus

- Grafana

## 🔄 Comunicação entre Microsserviços

- Arquitetura orientada a eventos (Event-Driven Architecture)

- Publicação e consumo de eventos de domínio (ex.: OrderCreated, PaymentApproved)

- Desacoplamento entre serviços

- Consistência eventual

🚀 Como executar o projeto (em breve)

As instruções de execução local utilizando Docker Compose serão adicionadas conforme o projeto evoluir.