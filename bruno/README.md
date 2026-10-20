# Coleção de desenvolvimento

Abra esta pasta no Bruno. A autenticação está em **Collection Settings → Auth**:
OAuth2 Authorization Code + PKCE (`S256`), realm `ecommerce`, client público
`ecommerce-dev`, callback `https://oauth.usebruno.com/callback`. O client não tem
secret. Clique em **Get Access Token** e faça login no Keycloak. O Bruno gerencia os
tokens localmente e a renovação; não copie tokens para headers ou arquivos da coleção.

Para trocar entre ADMIN e CUSTOMER, remova a credencial `ecommerce-user` na aba Auth e
obtenha outro token. `prompt=login` força a tela de login, mesmo com sessão SSO. Um
CUSTOMER pode se cadastrar na própria tela do Keycloak. Para ADMIN, use as credenciais
locais configuradas na implantação (`KC_ADMIN_USER_EMAIL` / `KC_ADMIN_USER_PASSWORD`).

| Ambiente | Destino | Requests |
| --- | --- | --- |
| Gateway | `http://localhost:8080` | Produto, estoque e pedidos pela API pública |
| ProductDirect | `http://localhost:8082` | Apenas produto |
| InventoryDirect | `http://localhost:8083` | Apenas estoque, incluindo o contrato interno |
| OrderDirect | `http://localhost:8081` | Apenas pedidos |

Todos usam `keycloakUrl=http://localhost:8180`. Direct usa os mesmos controles JWT
dos serviços; acesso direto não dispensa autorização. As portas Direct precisam
estar acessíveis localmente para executar esses ambientes.

| Operação | Autorização no gateway e no serviço |
| --- | --- |
| GET produto e estoque | Pública (`auth: none`) |
| Escrita de produto e cadastro/atualização de estoque | Token de usuário com role `ADMIN` |
| Reserva/liberação de estoque | Gateway: negada (403). InventoryDirect: token do client `order-service`, scope `inventory:reserve` |
| Criar pedido | Usuário autenticado; dono vem do claim `sub` (UUID Keycloak) |
| POST anônimo sem Bearer/cookie/CSRF (No Token) | Gateway: 403 pelo filtro CSRF da cadeia de sessão. Direct correspondente: 401 |
| Listar/ler/cancelar pedidos | Próprio `sub` ou `ADMIN`; pedido alheio responde 404 |

Os requests protegidos usam `auth: inherit`, inclusive os erros de validação, para
usar a identidade atual da coleção. O cenário de outro dono tem `auth: bearer`
próprio, com `secondUserAccessToken`, sem herdar o token atual.

## Variáveis e segredos locais

Na edição do ambiente no Bruno, preencha somente os segredos necessários:

- `secondUserAccessToken`: token PKCE de outro CUSTOMER, apenas para `Get Order Forbidden`.
  Obtenha o token pela Auth da coleção como segundo usuário e copie-o para essa variável
  secreta no ambiente selecionado. O pedido deve ter sido criado pelo primeiro usuário;
  nenhum deles pode ser ADMIN nesse teste.
- `orderServiceClientSecret`: apenas em `InventoryDirect`, com o valor local de
  `KC_ORDER_SERVICE_CLIENT_SECRET`. Reserve, release e reserva insuficiente usam OAuth2
  `client_credentials`, com `scope: inventory:reserve`, e recusam os outros ambientes
  antes do envio. Não use credenciais de serviço pelo gateway.

Os arquivos `vars:secret` versionam somente nomes; os valores permanecem no armazenamento
local do Bruno. Não grave senhas, access/refresh tokens ou secrets no repositório.
`productId`, `productName`, `inventorySkuCode` e `orderId` são variáveis de execução
preenchidas pelos requests. `inventoryItemId` precisa ser informado manualmente,
pois o DTO de estoque não expõe seu id numérico.

## Fluxo e verificações

1. Com ADMIN, crie um produto e insira estoque com `skuCode={{productId}}`.
2. Troque para CUSTOMER, crie e consulte um pedido. Se alternar entre ambientes Direct,
   obtenha o token no ambiente selecionado conforme necessário.
3. Verifique o outro dono com `Get Order Forbidden` (404), e depois ADMIN (leitura permitida).
4. Rode os três cenários **No Token** no Gateway e no Direct correspondente. Eles usam
   `auth: none`, mesmo com token válido salvo na coleção, e verificam a ausência de
   `Authorization` e `Cookie`. Desative cookies no Bruno ou limpe o cookie `SESSION`.
   No Gateway, o POST anônimo sem Bearer/cookie/CSRF entra na cadeia de sessão e recebe
   **403**, pois o filtro CSRF roda antes da autorização. No Direct correspondente,
   recebe **401** pelo Resource Server. Um Bearer inválido seleciona outra cadeia no
   gateway e recebe 401; não é o cenário No Token.
5. Escritas com CUSTOMER devem responder 403. GETs públicos devem funcionar sem token.
6. Em InventoryDirect, preencha o secret e execute reserve/release. A rota no gateway
   permanece negada (403), inclusive para ADMIN; para verificar essa negação, duplique
   localmente o request e use a autenticação de usuário herdada, sem o secret de serviço.

Na CLI, Authorization Code exige interação no aplicativo; a CLI não realiza o login
PKCE no navegador. Os cenários sem token e GETs públicos podem ser executados sem login:

```sh
cd bruno
bru run 'product-service/product-service-errors/Create Product No Token.bru' --env Gateway --disable-cookies
bru run 'inventory-service/inventory-service-errors/Insert No Token.bru' --env InventoryDirect --disable-cookies
bru run 'order-service/order-service-errors/Create Order No Token.bru' --env OrderDirect --disable-cookies
```

A coleção inclui operações que alteram dados. Execute os casos por cenário; não rode a
coleção inteira recursivamente sem preparar estoque, identidade e ambiente de cada etapa.

Formato conferido com o parser da CLI Bruno 4.2.0. Referências oficiais:
[Authorization Code](https://docs.usebruno.com/auth/oauth2-2.0/authorization-code),
[formato nativo da autenticação](https://github.com/usebruno/bruno/blob/main/packages/bruno-lang/v2/src/jsonToCollectionBru.js).
