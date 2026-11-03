# AGENTS.md — ecommerce-web

Instruções para qualquer agente de IA (ou pessoa) que trabalhe neste repositório. Leia tudo antes de alterar código.

## Regra principal

**Use sempre as melhores práticas consagradas no mercado** para código, pastas e organização: as do guia de estilo oficial do Angular (angular.dev/style-guide), da documentação do PrimeNG e do Tailwind, e de TypeScript estrito. Prefira a solução correta e robusta ao atalho. Isso vale para nomes, estrutura de pastas, separação de responsabilidades, segurança, acessibilidade, testes e performance.

Se algo existente no projeto fugir desses padrões, **aponte o problema, explique o porquê e proponha a correção**. Não copie um padrão ruim só porque ele já está no código.

Escreva respostas, comentários e mensagens de commit em português do Brasil.

## Stack

| Área                | Escolha                                       | Observação                                     |
| ------------------- | --------------------------------------------- | ---------------------------------------------- |
| Framework           | Angular 22, componentes standalone, signals   | Sem zone.js (zoneless); `OnPush` já é o padrão |
| Componentes visuais | PrimeNG 22 com tema Aura (`@primeuix/themes`) | Única biblioteca de componentes                |
| Layout              | Tailwind CSS 4 + `tailwindcss-primeui`        | Só classes utilitárias no HTML                 |
| Ícones              | PrimeIcons (`pi pi-*`)                        |                                                |
| Formulários         | Reactive Forms tipados                        |                                                |
| Testes unitários    | Vitest (`ng test`)                            |                                                |
| Testes E2E          | Playwright (pasta `tests/`)                   | Backend sempre mockado                         |
| Lint e formatação   | angular-eslint + Prettier                     |                                                |

**Não instale** sem pedir antes: outra biblioteca de componentes (`@angular/material` etc.), Sass, bibliotecas de estado (`@ngrx/*`) ou qualquer dependência nova. Toda dependência nova precisa de justificativa.

**Proibido** (quebra a arquitetura de segurança): `keycloak-js`, `keycloak-angular`, `angular-oauth2-oidc`, `angular-auth-oidc-client`, `jwt-decode`, `ngx-cookie-service` e qualquer código que guarde token em `localStorage`, `sessionStorage` ou cookie.

## Estilização

- **Não escreva CSS à mão.** Use componentes do PrimeNG e classes do Tailwind (`flex`, `grid`, `gap-4`, `p-4`, `bg-surface-0`, `text-primary`...).
- Componentes não têm arquivo `.css` (o `angular.json` já gera sem). Crie um apenas quando não houver alternativa, e explique o motivo.
- Identidade visual (cores, fontes, arredondamento) muda **somente** pelo preset do tema: `definePreset(Aura, {...})` em `src/app/app.config.ts`. Nunca sobrescreva classes `.p-*` do PrimeNG.
- Use as cores do tema via `tailwindcss-primeui` (`bg-primary`, `text-primary-contrast`, `bg-surface-100`...) em vez de cores fixas (`bg-green-500`), para o tema e o modo escuro funcionarem.
- Modo escuro: classe `.p-dark` no `<html>` (já ligada ao `dark:` do Tailwind em `src/styles.css`). Não altere a ordem de `@layer` em `src/styles.css`.
- Layout responsivo (mobile first) e acessível: HTML semântico, `alt` em imagens, `label` em campos, foco visível, navegação por teclado.

## Organização de pastas

Organize **por funcionalidade (feature)**, não por tipo de arquivo:

```text
src/app/
├── core/                    # Singletons da aplicação, carregados uma vez
│   ├── auth/                # AuthService, authGuard, adminGuard, modelo do usuário
│   ├── http/                # Interceptors (ex.: unauthorizedInterceptor), tratamento de ProblemDetail
│   └── layout/              # Shell da aplicação: header, footer, menu
├── shared/                  # Reutilizável e sem regra de negócio: componentes de UI, pipes, diretivas, utils
├── features/
│   ├── catalog/             # Ex.: listagem e detalhe de produtos
│   │   ├── data-access/     # Services HTTP e modelos (interfaces) da feature
│   │   ├── product-list/    # Uma pasta por componente/página
│   │   ├── product-detail/
│   │   └── catalog.routes.ts
│   ├── cart/
│   ├── orders/
│   └── admin/
├── app.config.ts
├── app.routes.ts
└── app.ts / app.html
```

Regras:

- Uma feature não importa arquivos de dentro de outra feature. O que for compartilhado vai para `shared/` ou `core/`.
- `shared/` não depende de `features/`. `core/` não depende de `features/`.
- Nomes de arquivo em `kebab-case`, seguindo o guia de estilo atual do Angular: `product-list.ts` com a classe `ProductList`, sem o sufixo `.component` (o mesmo padrão que o CLI gera).
- Um conceito por arquivo; arquivos pequenos e focados.
- Gere arquivos com o CLI (`ng generate component|service|guard|interceptor ...`) para manter as convenções.

## Código

- Componentes: `input()`, `output()`, `model()`, `computed()` e `signal()`; `inject()` em vez de injeção pelo construtor.
- Templates com o controle de fluxo nativo (`@if`, `@for` com `track`, `@switch`), nunca `*ngIf`/`*ngFor`.
- Componentes de página (smart) buscam dados via services; componentes de apresentação (dumb) só recebem `input()` e emitem `output()`.
- Chamadas HTTP ficam em services de `data-access/`, nunca direto no componente. Tipe as respostas com interfaces.
- Para dados do servidor em componentes, prefira `httpResource()`/`resource()` ou `toSignal()`; evite `subscribe` manual (se usar, encerre com `takeUntilDestroyed()`).
- Todas as rotas de feature com lazy loading (`loadComponent` / `loadChildren`). Isso também mantém o bundle inicial dentro do limite de 500 kB do `angular.json`.
- TypeScript estrito: sem `any` (use `unknown` e estreite o tipo), sem `@ts-ignore`, sem `!` para silenciar o compilador.
- Segurança: nunca use `bypassSecurityTrust*` nem `[innerHTML]` com dados do usuário; nunca coloque segredo no código do frontend (tudo nele é público).
- Trate estados de carregamento, vazio e erro em toda tela que busca dados. Mostre erros com o `Toast`/`Message` do PrimeNG usando o `detail` do ProblemDetail.

## Contrato com o backend (padrão BFF)

O backend usa o padrão BFF: o api-gateway (`http://localhost:8080`) faz o login OIDC com o Keycloak e guarda os tokens na sessão do servidor. **O frontend nunca vê tokens**; o navegador só tem o cookie de sessão `HttpOnly`.

1. **Proxy de desenvolvimento:** `proxy.conf.json`, referenciado em `angular.json` (`serve.options.proxyConfig`), apontando para `http://localhost:8080` com `"changeOrigin": false` nos prefixos `/api`, `/bff`, `/oauth2`, `/login/oauth2` e `/logout`. **Não crie rotas Angular começando com esses prefixos.** Use `/login/oauth2` (e não `/login`) para não capturar uma rota `/login` do Angular.
2. **URLs relativas sempre:** `/api/product`, nunca `http://localhost:8080/api/product`. O `HttpClient` só envia o header CSRF para URLs relativas.
3. **`app.config.ts`:**
   ```ts
   provideHttpClient(
     withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
     withInterceptors([unauthorizedInterceptor]),
   );
   ```
4. **Endpoints:**

   | Ação           | Como                                                                                                                                  |
   | -------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
   | Usuário logado | `GET /bff/me` → `200 { sub, name, email, roles: string[] }` ou `401` se não logado                                                    |
   | Login          | `window.location.href = '/oauth2/authorization/keycloak'` (navegação completa, não `HttpClient`; volta para `http://localhost:4200/`) |
   | Logout         | Submeter um `<form method="POST" action="/logout">` com o campo hidden `_csrf` = valor do cookie `XSRF-TOKEN` (navegação completa)    |
   | Produtos       | `GET /api/product` e `GET /api/product/{id}` públicos; `POST/PUT/DELETE /api/product` só `ADMIN`                                      |
   | Estoque        | `GET /api/inventory?skuCode=...` público                                                                                              |
   | Pedidos        | `GET/POST /api/order[/{id}]` com usuário logado (`CUSTOMER` vê só os próprios pedidos)                                                |

5. **`AuthService`** (`core/auth/`) com um signal do usuário carregado de `GET /bff/me`. `authGuard` funcional: se `401`, manda para o login. `adminGuard`: verifica `roles.includes('ADMIN')`. Os guards protegem a navegação, mas a segurança real é do backend.
6. **`unauthorizedInterceptor`:** resposta `401` em `/api/**` limpa o usuário e manda para o login (a sessão expirou).
7. **Erros** vêm como ProblemDetail (RFC 9457): `{ status, title, detail, errors? }`.

Os endpoints `/bff/*`, o login e o logout ainda estão sendo implementados no backend. Até lá, desenvolva com dados mockados e teste com mocks.

## Testes

- Todo componente, service, guard e interceptor novo vem com teste unitário (`*.spec.ts` ao lado do arquivo).
- Teste comportamento (o que o usuário vê e o que é chamado), não detalhes de implementação.
- HTTP em teste unitário: `provideHttpClientTesting()` + `HttpTestingController`.
- E2E (Playwright, pasta `tests/`): mocke o backend com `page.route('/api/**', ...)` e `page.route('/bff/me', ...)`; nunca dependa do backend real nem de sites externos. Prefira seletores por papel e texto (`getByRole`, `getByLabel`).

## Antes de dizer que terminou

Rode e garanta que tudo passa, sem erros nem warnings novos:

```bash
npm run lint
npm run build
npm test -- --watch=false
npx playwright test        # quando houver testes E2E
npx prettier --check .
```

Se algo falhar, corrija ou diga claramente o que falhou. Não declare a tarefa concluída sem rodar esses comandos.

## Git

- Commits pequenos, em português, no padrão Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`).
- Não faça commit de `dist/`, `.angular/`, `node_modules/`, relatórios do Playwright ou arquivos `.env`.
