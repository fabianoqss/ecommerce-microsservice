# E-commerce baseado em microsserviços

Backend de e-commerce com Spring Boot, Spring Cloud, identidade OIDC via Keycloak, comunicação síncrona e assíncrona e observabilidade.

O fluxo principal inclui estoque atômico, cancelamento de pedidos, Outbox, DLQ e autorização por dono do pedido. O gateway oferece login pelo navegador como BFF e acesso à API com Bearer token.

## Arquitetura atual

| Componente | Porta no container | Persistência | Responsabilidade |
|---|---:|---|---|
| API Gateway | 8080; gerenciamento 8090 | Redis | BFF OIDC, sessão, token relay e autorização de borda |
| Product Service | 8082 | MongoDB | Catálogo e CRUD de produtos |
| Inventory Service | 8083 | PostgreSQL | Consulta, manutenção e reserva atômica de estoque |
| Order Service | 8081 | PostgreSQL | Pedidos, cancelamento, compensação e Outbox |
| Notification Service | 8084 | Redis | Consumo idempotente de eventos, retry e DLQ; notificação no log |
| Keycloak | 8080; gerenciamento 9000 | PostgreSQL | Identidade, cadastro, login, recuperação de senha e emissão de tokens |

As cinco aplicações Java são Gateway, Product, Inventory, Order e Notification; o sexto módulo é a biblioteca `common-security`. Os serviços de negócio validam assinatura, issuer e audience dos tokens localmente. A identidade do pedido é o `sub` do Keycloak, e as roles de usuário são `CUSTOMER` e `ADMIN`.

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

Rede do Compose: resolução dos serviços por DNS
Prometheus/Grafana: métricas; Zipkin: traces
```

## Tecnologias

- Java 21, Spring Boot 4.0.x e Spring Cloud 2025.1.x
- Spring MVC, Data JPA, Data MongoDB e Flyway
- Spring Cloud Gateway Server Web MVC e OpenFeign
- Spring Security OAuth2/OIDC Resource Server e Client; Authorization Code com PKCE
- PostgreSQL, MongoDB, RabbitMQ e Redis
- Docker Compose, Actuator, Prometheus, Grafana, Zipkin e Springdoc OpenAPI

## Endpoints

As chamadas externas à aplicação passam por `http://localhost:8080`. No Compose, as portas de Product, Inventory, Order, Notification e do Actuator do gateway não são publicadas no host.

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

Importe o `pom.xml` da raiz como projeto Maven com JDK 21. O parent agrega os cinco serviços e o JAR `common-security`, que concentra apenas a conversão de autoridades Keycloak. O Maven Wrapper é único na raiz (Maven 3.9.12); no Windows, use `mvnw.cmd`.

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
| _(nenhum)_ | `keycloak`, `keycloak-config`, `rabbitmq`, `rabbitmq-init`, `redis`; em dev também `postgres`, `mongo`, `mailpit` |
| `monitoring` | `zipkin`, `prometheus`, `grafana` |
| `apps` | `api-gateway`, `product-service`, `inventory-service`, `order-service`, `notification-service` |

O `.env.example` da raiz é a referência oficial e define `COMPOSE_PROFILES=monitoring,apps`. Endereços e segredos são configuráveis por ambiente; variáveis obrigatórias usam `${VAR:?}` para o Compose rejeitar configuração incompleta.

### Configuração do ambiente

Na raiz do repositório:

```bash
./scripts/generate-env.sh
```

O script cria `.env` a partir do exemplo e substitui **cada** valor `troque-...` por um segredo independente de 24 bytes gerado por `openssl rand -hex 24`. Não imprime os segredos e cria o arquivo com permissão `600`. Recusa sobrescrever um `.env` existente; `./scripts/generate-env.sh --force` cria um backup com permissão `600` antes da substituição. O `.env` e seus backups contêm credenciais e não devem ser compartilhados ou versionados.

Em desenvolvimento, Order e Inventory usam o usuário `postgres` do container local: a senha efetiva é `DB_PASSWORD`. O exemplo define `ORDER_DB_PASSWORD=${DB_PASSWORD}` e `INVENTORY_DB_PASSWORD=${DB_PASSWORD}`, referências expandidas pelo Compose. Em produção, configure usuários e senhas próprios para cada banco e gere as variáveis pelo OCI Vault. O banco do Keycloak tem usuário e senha separados.

Os endereços dos serviços são configuração de ambiente: o gateway usa `PRODUCT_SERVICE_URL`, `ORDER_SERVICE_URL` e `INVENTORY_SERVICE_URL`; o Order usa somente `PRODUCT_SERVICE_URL` e `INVENTORY_SERVICE_URL`. No Compose, essas variáveis apontam para `http://product-service:8082`, `http://order-service:8081` e `http://inventory-service:8083`, resolvidos pelo DNS da rede dos containers. Cada consumidor aguarda os healthchecks das suas dependências. Pela IDE, os valores padrão são `http://localhost:8082`, `http://localhost:8081` e `http://localhost:8083`. A resolução de nomes fica a cargo da plataforma: Docker Compose hoje, Kubernetes Service futuramente.

### Realm do Keycloak

O realm `ecommerce` é código: [`keycloak/realm-ecommerce.yaml`](keycloak/realm-ecommerce.yaml). A cada subida do Compose, o serviço de execução única `keycloak-config` ([keycloak-config-cli](https://github.com/adorsys/keycloak-config-cli)) espera o Keycloak ficar saudável e reconcilia o realm com o arquivo; as aplicações que validam tokens só iniciam depois que ele termina com código `0`. O cache de importação fica desligado, então qualquer configuração declarada no arquivo que for alterada à mão no console volta ao valor do arquivo na próxima subida: mude o realm pelo arquivo, em PR. A ferramenta só remove recursos que ela mesma criou; um client ou role criado à mão fora do arquivo continua existindo e deve ser removido manualmente.

Secrets, URL pública e SMTP entram por variáveis de ambiente (`KC_BFF_CLIENT_SECRET`, `KC_ORDER_SERVICE_CLIENT_SECRET`, `GATEWAY_PUBLIC_URL`, `KC_SMTP_*`), substituídas na hora da aplicação; o mesmo valor do `.env`/Vault chega ao Keycloak e ao serviço que usa o client. Esses valores devem usar só caracteres seguros (`A–Z`, `a–z`, `0–9`, `_`, `.`, `~` e `-`), como os gerados por `scripts/generate-env.sh`. O arquivo não define usuários: clientes se cadastram pela página do Keycloak e recebem só a role `CUSTOMER`; `ADMIN` é atribuída manualmente.

| Client | Configuração |
|---|---|
| `ecommerce-bff` | Confidencial; Authorization Code com PKCE S256; redirect exato `${GATEWAY_PUBLIC_URL}/login/oauth2/code/keycloak`; sem Password Grant, Implicit, device flow ou `offline_access`; tokens só com as roles `CUSTOMER`/`ADMIN`, também no ID token (lido pelo `/bff/me`) |
| `order-service` | Confidencial; só service account (`client_credentials`); scope `inventory:reserve`; sem roles de usuário no token |

O realm exige HTTPS fora da rede local, verificação de e-mail, senha forte com histórico, bloqueio progressivo contra força bruta, access token de 5 minutos e sessão limitada a 30 minutos ociosa ou 10 horas no total, e registra eventos de login e administrativos (sem o corpo das requisições, que incluiria secrets). Os access tokens levam `aud=ecommerce-api`. No ambiente local, o issuer público é `http://localhost:8180/realms/ecommerce`; containers consultam JWKs e token endpoint pela rede interna. As variáveis `OIDC_*` controlam esses endereços. Em desenvolvimento, o SMTP aponta para o Mailpit (`http://localhost:8025`).

Para produção com HTTPS, consulte [Deploy na OCI](#deploy-na-oci). O override de desenvolvimento usa `SESSION_COOKIE_SECURE=false` para HTTP local.

### Inicialização

Para produção, siga [Deploy na OCI](#deploy-na-oci). Para desenvolvimento local:

```bash
# Desenvolvimento local: infraestrutura e aplicações
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
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

O Actuator do gateway usa a porta interna `8090`, sem publicação no host. O Prometheus coleta as métricas pela rede interna. A amostragem de traces é `1.0` em dev e, por padrão, `0.1` em produção.

### Execução pela IDE

Remova `COMPOSE_PROFILES` do `.env` para subir só a infraestrutura; para manter o monitoramento, use `COMPOSE_PROFILES=monitoring`. Execute o mesmo comando Compose de desenvolvimento. Importe o `pom.xml` da raiz na IDE, aguarde a sincronização Maven e configure o JDK 21. Crie uma configuração Spring Boot para a classe principal anotada com `@SpringBootApplication` de cada serviço, usando o classpath do respectivo módulo; `common-security` é uma biblioteca, sem aplicação própria. Use o diretório do serviço como diretório de trabalho e configure as variáveis emitidas pelo script:

```bash
./scripts/ide-env.sh
```

O script lê o `.env` da raiz e emite as variáveis para Product, Inventory, Order, Notification e Gateway, incluindo os endereços locais necessários. Copie cada bloco para a configuração do módulo correspondente na IDE. **A saída contém segredos: execute apenas no seu terminal e não cole em chats, logs ou arquivos versionados.**

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

## Deploy na OCI

Execute na ordem abaixo. `ecommerce-vm` é um alias SSH configurado na máquina de administração para o usuário da VM com chave e sudo. Os scripts não criam os recursos OCI. Endpoints e domínios estão fixados em `scripts/prod/prod.env`, nos bootstraps dos bancos e na configuração da borda; se mudarem ao recriar a infraestrutura, atualize os arquivos no git antes do deploy. Nunca edite arquivos na VM: toda mudança de configuração passa pelo git; o `.env` é gerado.

1. **Pré-requisitos na OCI.** Provisione VM Oracle Linux 9, com `firewalld` ativo, dynamic group incluindo a instância e policy permitindo ler os segredos do Vault via instance principal. Provisione PostgreSQL gerenciado (o bootstrap usa cliente 17) com endpoint privado e Autonomous JSON com endpoint privado e API Mongo habilitada. A VM precisa resolver e alcançar os bancos nas portas privadas `5432` e `27017`, além de saída para repositórios, imagens, APIs OCI, ACME e SMTP `587`.

   Na Security List da VM, abra somente TCP `80`/`443` para a borda e `22` para SSH (restrito à origem administrativa). Não publique portas dos bancos ou consoles. Crie registros DNS A `loja.fabianoquirinos.com.br` e `auth.fabianoquirinos.com.br` apontando para o IP público da VM. No Email Delivery, aprove o remetente `noreply@loja.fabianoquirinos.com.br` e gere credenciais SMTP.

   Crie um Vault e uma chave de criptografia. Os 15 nomes exigidos por `SECRET_NAMES` em `generate-prod-env.sh` são:

   ```text
   KEYCLOAK_ADMIN_PASSWORD       KEYCLOAK_DB_PASSWORD
   KC_BFF_CLIENT_SECRET         KC_ORDER_SERVICE_CLIENT_SECRET
   KC_SMTP_USER                 KC_SMTP_PASSWORD
   ORDER_DB_PASSWORD            INVENTORY_DB_PASSWORD
   RABBITMQ_ADMIN_PASSWORD      RABBITMQ_ORDER_PASSWORD
   RABBITMQ_NOTIFICATION_PASSWORD  RABBITMQ_INVENTORY_PASSWORD
   REDIS_PASSWORD               GRAFANA_ADMIN_PASSWORD
   PRODUCT_MONGODB_PASSWORD
   ```

   Cadastre `KC_SMTP_USER` e `KC_SMTP_PASSWORD` com as credenciais reais do Email Delivery, não valores aleatórios. Cadastre também `prod-postgres-password` e `prod-mongoDB-password` com as senhas administrativas reais dos bancos; os bootstraps leem esses dois segredos adicionais. Valores não podem ser vazios nem conter quebras de linha ou aspas simples.

2. **Bootstrap da VM.** Na máquina de administração, a partir de um checkout de `main`, copie o script para a VM e execute como root via sudo:

   ```bash
   scp scripts/prod/vm-bootstrap.sh ecommerce-vm:/tmp/vm-bootstrap.sh
   ssh -t ecommerce-vm 'sudo bash /tmp/vm-bootstrap.sh'
   ```

   O script expande o disco com `oci-growfs` quando disponível, atualiza pacotes, ativa atualizações automáticas de segurança sem reboot automático e instala Git, OCI CLI, Docker Engine e Compose. Configura rotação de logs (`10m`, cinco arquivos), `live-restore` e SSH só por chave, sem login root, autenticação interativa ou X11, com três tentativas. Se indicar reinício, execute `sudo systemctl reboot` na VM e reconecte.

3. **Clone como root, somente `main`.** Entre na VM; os comandos seguintes, salvo indicação contrária, rodam nela:

   ```bash
   ssh ecommerce-vm
   sudo git clone --branch main --single-branch https://github.com/fabianoqss/ecommerce-microsservice.git /opt/ecommerce
   cd /opt/ecommerce
   ```

   Atualizações usam `sudo git pull --ff-only`; não crie alterações locais na VM.

4. **Borda HTTPS.** Com DNS propagado e porta `80` acessível:

   ```bash
   sudo ./scripts/prod/edge-setup.sh
   ```

   Instala nginx do AppStream e certbot via EPEL restrito aos pacotes do certbot e suas dependências listadas. Libera HTTP/HTTPS no `firewalld`, habilita `httpd_can_network_relay` no SELinux e rotula `8180` como `http_port_t` (`8080` já é `http_cache_port_t`). Emite certificado Let's Encrypt por HTTP-01 para os dois domínios, instala `scripts/prod/nginx/ecommerce.conf` e ativa `certbot-renew.timer`, com reload do nginx após renovação. A borda redireciona HTTP para HTTPS e usa TLS 1.2/1.3; antes da subida dos containers, os proxies ainda podem retornar `502`.

5. **Segredos aleatórios.** Na máquina de administração, use OCI CLI autenticada como usuário autorizado a consultar o Vault e gerenciar segredos, além de Python 3. Na raiz do checkout, informe os OCIDs do Vault e da chave:

   ```bash
   read -r -p 'OCID do Vault: ' VAULT_ID
   read -r -p 'OCID da chave: ' KEY_ID
   for name in KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASSWORD \
     KC_BFF_CLIENT_SECRET KC_ORDER_SERVICE_CLIENT_SECRET \
     ORDER_DB_PASSWORD INVENTORY_DB_PASSWORD RABBITMQ_ADMIN_PASSWORD \
     RABBITMQ_ORDER_PASSWORD RABBITMQ_NOTIFICATION_PASSWORD \
     RABBITMQ_INVENTORY_PASSWORD REDIS_PASSWORD GRAFANA_ADMIN_PASSWORD \
     PRODUCT_MONGODB_PASSWORD; do
     ./scripts/prod/vault-random-secret.sh "$name" "$VAULT_ID" "$KEY_ID" 30
   done
   ```

   Argumentos: `<nome> <vault-ocid> <key-ocid> [tamanho]`; padrão `30`, intervalo `12–30`. Gera senha alfanumérica com maiúscula, minúscula e dígito, sem exibir o valor; segredo existente é preservado. Não use para gerar novamente senhas administrativas de bancos já criados nem credenciais SMTP.

6. **Bancos.** De volta à VM, em `/opt/ecommerce`, informe o mesmo Vault:

   ```bash
   read -r -p 'OCID do Vault: ' VAULT_ID
   sudo ./scripts/prod/db-bootstrap.sh "$VAULT_ID"
   sudo ./scripts/prod/adb-bootstrap.sh "$VAULT_ID"
   ```

   O primeiro lê o Vault por instance principal e usa `psql` em `postgres:17-alpine` para reconciliar os usuários `order_service`, `inventory_service` e `keycloak`, donos dos bancos `order-service`, `inventory_service` e `keycloak`, com acesso de `PUBLIC` revogado. Usa `verify-full`; `scripts/prod/certs/oci-postgresql-ca.pem` é a CA pública para validar certificado e hostname do PostgreSQL, também montada somente para leitura nos containers pelo override de produção. Ao recriar o banco, confira a CA fornecida pela OCI e atualize-a pelo git se necessário.

   O segundo usa `mongosh` em `mongo:7.0`, via API Mongo com TLS na porta `27017`, autenticado como `ADMIN`. Cria/atualiza `PRODUCT_SERVICE`, cota de `5G`, privilégios de schema e habilitação ORDS com autenticação; testa escrita/leitura e remove a coleção de teste. Não usa SQL*Net `1522` nem ORDS `443`. A senha de `PRODUCT_SERVICE` deve ter `12–30` caracteres alfanuméricos, maiúscula, minúscula e dígito, sem conter o nome do usuário.

7. **`.env` a partir do Vault.** Na VM:

   ```bash
   sudo ./scripts/prod/generate-prod-env.sh "$VAULT_ID"
   ```

   Usa instance principal, combina `scripts/prod/prod.env` com os 15 segredos e monta `PRODUCT_MONGODB_URI`. O template define URLs públicas HTTPS e `SESSION_COOKIE_SECURE=true`. Valida com Compose, substitui atomicamente o `.env` com dono root e modo `600`, preservando backup `600` se já existir. Não imprime segredos. Não execute o `.env` como shell nem compartilhe o arquivo ou seus backups.

8. **Subida.** Na VM, em `/opt/ecommerce`:

   ```bash
   sudo docker compose up -d --build --remove-orphans
   ```

   Construa e suba no mesmo comando: um `build` separado pode gerar imagens novas sem que o `up` recrie os containers, que seguem com a imagem anterior. O `.env` define `COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml` e `COMPOSE_PROFILES=monitoring,apps`. Na primeira aplicação do `frontendUrl` do master, `keycloak-config` pode sair com `401`: o atributo já foi gravado, mas o token do CLI ainda tem o issuer anterior (comentários em `scripts/prod/keycloak/realm-master.yaml`). Nesse caso, execute novamente e confirme saída `0`; depois retome a subida das aplicações:

   ```bash
   sudo docker compose run --rm keycloak-config
   sudo docker compose up -d --remove-orphans
   sudo docker compose ps -a
   ```

9. **Acesso administrativo.** Somente por túnel SSH, iniciado na máquina de administração:

   ```bash
   ssh -o ExitOnForwardFailure=yes -L 8180:localhost:8180 ecommerce-vm
   ```

   Abra `http://localhost:8180/admin/` (`admin` / `KEYCLOAK_ADMIN_PASSWORD`). Para os demais consoles, use o mesmo comando trocando ambos os números de porta: RabbitMQ `15672`, Grafana `3000`, Prometheus `9090`, Zipkin `9411`. Todos são publicados somente em loopback. No domínio público de autenticação, nginx bloqueia `/admin` e `/realms/master` com `404`; apenas o realm de negócio e seus recursos ficam acessíveis.

10. **Atualização de versão.** Publique toda mudança em `main` antes de atualizar. Na VM:

    ```bash
    cd /opt/ecommerce
    sudo git pull --ff-only
    sudo docker compose up -d --build --remove-orphans
    sudo docker compose ps -a
    ```

    Se configuração pública ou segredos mudaram, regenere o `.env` pelo passo 7 antes da subida; mudanças na borda exigem reaplicar o passo 4. Alterar um segredo não rotaciona automaticamente credenciais persistidas: coordene a rotação com cada serviço/banco. Após a primeira subida e a cada atualização, aguarde os healthchecks e execute os smoke tests sem sessão (saídas esperadas: `200`, `401`, `403`):

    ```bash
    curl -sS -o /dev/null -w '%{http_code}\n' https://loja.fabianoquirinos.com.br/api/product
    curl -sS -o /dev/null -w '%{http_code}\n' https://loja.fabianoquirinos.com.br/api/order
    curl -sS -o /dev/null -w '%{http_code}\n' https://loja.fabianoquirinos.com.br/actuator/health
    ```

## Testes

Use o Maven Wrapper único, a partir da raiz:

```bash
./mvnw -pl order-service -am test
./mvnw -pl api-gateway -am test
python3 -m unittest discover -s scripts/tests -v
```

Há testes unitários, de autorização MVC, de sessão/Bearer no gateway e de integração do fluxo Order/Inventory. Os testes que usam Testcontainers precisam de Docker acessível. A cobertura ainda deve ser ampliada, especialmente para eventos e o fluxo ponta a ponta.

As chamadas externas passam pelo gateway; o login de usuário usa Authorization Code com PKCE. As rotas internas de reserva (`/api/inventory/reservations/{reservationId}`) usam `client_credentials` do `order-service` e permanecem bloqueadas no gateway.

## Escopo

Não há frontend, Payment Service ou AI Service neste repositório. A prioridade é estabilizar e ampliar a validação dos seis módulos Java atuais antes de adicionar novos serviços ou interface gráfica.
