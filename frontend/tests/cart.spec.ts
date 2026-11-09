import { expect, test } from '@playwright/test';

const product = {
  id: '670000000000000000000001',
  name: 'Teclado Mecânico TKL',
  description: 'Teclado compacto.',
  price: 349.9,
  category: 'Periféricos',
  imageUrl: null,
  version: 0,
};
const user = { sub: 'ana', name: 'Ana Silva', email: 'ana@example.com', roles: ['CUSTOMER'] };

test.beforeEach(async ({ page }) => {
  await page.route('**/bff/me', (route) => route.fulfill({ json: user }));
  await page.route('**/api/product/**', (route) => route.fulfill({ json: product }));
  await page.route('**/api/inventory**', (route) =>
    route.fulfill({
      json: [{ skuCode: product.id, quantity: 10, inStock: true }],
    }),
  );
});

test('visitante não vê carrinho e inicia login ao adicionar produto', async ({ page }) => {
  await page.route('**/bff/me', (route) => route.fulfill({ status: 401, json: {} }));
  await page.route('**/oauth2/authorization/keycloak', (route) =>
    route.fulfill({ contentType: 'text/html', body: '<p>Login</p>' }),
  );
  await page.goto('/catalog/' + product.id);
  await expect(page.getByRole('link', { name: /carrinho/i })).toHaveCount(0);
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await expect(page).toHaveURL('/oauth2/authorization/keycloak');
});

test('adiciona produto, mostra toast e preserva badge após recarregar', async ({ page }) => {
  await page.goto('/catalog/' + product.id);
  const cart = page.getByRole('link', { name: 'Carrinho, 0 itens', exact: true });
  await expect(cart).toBeVisible();
  await expect(cart).toHaveText('');
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await expect(page.getByRole('link', { name: 'Carrinho, 1 item', exact: true })).toHaveText('1');
  await expect(page.getByRole('alert')).toContainText('Produto adicionado ao carrinho');
  await expect(page.getByRole('link', { name: 'Ver carrinho', exact: true })).toHaveAttribute(
    'href',
    '/cart',
  );
  await expect(page).toHaveURL('/catalog/' + product.id);
  await page.reload();
  await expect(page.getByRole('link', { name: 'Carrinho, 1 item', exact: true })).toHaveText('1');
  expect(await page.evaluate(() => localStorage.getItem('cart:ana'))).toBe(
    '[{"productId":"670000000000000000000001","quantity":1}]',
  );
});

test('mostra carrinho no menu móvel com área de toque acessível', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/catalog/' + product.id);
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await page.getByRole('button', { name: 'Abrir menu' }).click();
  const cart = page
    .getByRole('navigation', { name: 'Navegação móvel' })
    .getByRole('link', { name: 'Carrinho, 1 item', exact: true });
  await expect(cart).toBeVisible();
  await expect(cart).toHaveAttribute('href', '/cart');
  const bounds = await cart.boundingBox();
  expect(bounds?.width).toBeGreaterThanOrEqual(44);
  expect(bounds?.height).toBeGreaterThanOrEqual(44);
});

test('sincroniza o carrinho entre abas da mesma conta', async ({ page, context }) => {
  await page.goto('/catalog/' + product.id);
  const second = await context.newPage();
  await second.route('**/bff/me', (route) => route.fulfill({ json: user }));
  await second.route('**/api/product/**', (route) => route.fulfill({ json: product }));
  await second.route('**/api/inventory**', (route) =>
    route.fulfill({ json: [{ skuCode: product.id, quantity: 10, inStock: true }] }),
  );
  await second.goto('/catalog/' + product.id);
  await expect(second.getByRole('link', { name: 'Carrinho, 0 itens', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await expect(second.getByRole('link', { name: 'Carrinho, 1 item', exact: true })).toHaveText('1');
});

test('outra conta não vê o carrinho anterior', async ({ page }) => {
  await page.goto('/catalog/' + product.id);
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await expect(page.getByRole('link', { name: 'Carrinho, 1 item', exact: true })).toBeVisible();
  await page.route('**/bff/me', (route) =>
    route.fulfill({ json: { ...user, sub: 'bia', name: 'Bia' } }),
  );
  await page.reload();
  await expect(page.getByRole('link', { name: 'Carrinho, 0 itens', exact: true })).toBeVisible();
});

test('revisa dois produtos, altera quantidade e remove com subtotal e badge atualizados', async ({
  page,
}) => {
  const mouse = { ...product, id: '670000000000000000000002', name: 'Mouse', price: 100 };
  await page.route('**/api/product/**', (route) =>
    route.fulfill({ json: route.request().url().endsWith(mouse.id) ? mouse : product }),
  );
  await page.route('**/api/inventory**', (route) => {
    const skus = new URL(route.request().url()).searchParams.getAll('skuCode');
    return route.fulfill({
      json: skus.map((skuCode) => ({ skuCode, quantity: 10, inStock: true })),
    });
  });
  await page.goto('/catalog/' + product.id);
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await page.goto('/catalog/' + mouse.id);
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await page.getByRole('link', { name: 'Carrinho, 2 itens', exact: true }).click();
  await expect(page).toHaveURL('/cart');
  await expect(page).toHaveTitle('Carrinho | DS Catalog');
  const summary = page.getByRole('complementary', { name: 'Resumo do pedido' });
  await expect(summary).toContainText('R$ 449,90');
  const quantity = page.getByRole('spinbutton', {
    name: 'Quantidade de Teclado Mecânico TKL',
    exact: true,
  });
  await quantity.fill('2');
  await quantity.press('Tab');
  await expect(page.getByRole('link', { name: 'Carrinho, 3 itens', exact: true })).toHaveText('3');
  await expect(summary).toContainText('R$ 799,80');
  await page.getByRole('button', { name: 'Remover Mouse', exact: true }).click();
  await expect(page.getByRole('article', { name: 'Mouse', exact: true })).toHaveCount(0);
  await expect(summary).toContainText('R$ 699,80');
  await expect(page.getByRole('link', { name: 'Carrinho, 2 itens', exact: true })).toHaveText('2');
  await expect(page.getByRole('button', { name: 'Continuar para o pedido' })).toBeEnabled();
});

test('remove um de dois produtos mantendo a outra linha visível sem recarregar os dados', async ({
  page,
}) => {
  const mouse = { ...product, id: '670000000000000000000002', name: 'Mouse', price: 100 };
  const requests: string[] = [];
  await page.addInitScript(
    (items) => localStorage.setItem('cart:ana', JSON.stringify(items)),
    [
      { productId: product.id, quantity: 1 },
      { productId: mouse.id, quantity: 2 },
    ],
  );
  await page.route('**/api/product/**', (route) => {
    requests.push(route.request().url());
    return route.fulfill({ json: route.request().url().endsWith(mouse.id) ? mouse : product });
  });
  await page.route('**/api/inventory**', (route) => {
    requests.push(route.request().url());
    const skus = new URL(route.request().url()).searchParams.getAll('skuCode');
    return route.fulfill({
      json: skus.map((skuCode) => ({ skuCode, quantity: 10, inStock: true })),
    });
  });
  await page.goto('/cart');
  const remaining = page.getByRole('article', { name: 'Mouse', exact: true });
  const summary = page.getByRole('complementary', { name: 'Resumo do pedido' });
  await expect(remaining).toBeVisible();
  await expect(summary).toContainText('R$ 549,90');
  expect(requests).toHaveLength(3);
  const initialRequests = [...requests];
  await page.getByRole('button', { name: 'Remover Teclado Mecânico TKL', exact: true }).click();
  await expect(page.getByRole('article', { name: product.name, exact: true })).toHaveCount(0);
  await expect(remaining).toBeVisible();
  await expect(remaining.getByRole('spinbutton')).toHaveValue('2');
  await expect(summary).toContainText('R$ 200,00');
  await expect(page.getByRole('button', { name: 'Continuar para o pedido' })).toBeEnabled();
  expect(requests).toEqual(initialRequests);
});

test('carrinho vazio oferece catálogo sem redirecionar', async ({ page }) => {
  await page.goto('/cart');
  await expect(page.getByText('Seu carrinho está vazio', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Ver catálogo', exact: true })).toHaveAttribute(
    'href',
    '/catalog',
  );
  await expect(page).toHaveURL('/cart');
});

test('acesso direto ao carrinho exige login', async ({ page }) => {
  await page.route('**/bff/me', (route) => route.fulfill({ status: 401, json: {} }));
  await page.route('**/oauth2/authorization/keycloak', (route) =>
    route.fulfill({ contentType: 'text/html', body: '<p>Login</p>' }),
  );
  await page.goto('/cart');
  await expect(page).toHaveURL('/oauth2/authorization/keycloak');
});

for (const state of ['deleted', 'empty', 'insufficient'] as const) {
  test(`bloqueia pedido com produto ${state} e permite remover`, async ({ page }) => {
    await page.addInitScript(
      ({ id }) => {
        localStorage.setItem('cart:ana', JSON.stringify([{ productId: id, quantity: 2 }]));
      },
      { id: product.id },
    );
    if (state === 'deleted') {
      await page.route('**/api/product/**', (route) => route.fulfill({ status: 404, json: {} }));
    }
    await page.route('**/api/inventory**', (route) =>
      route.fulfill({
        json: [
          { skuCode: product.id, quantity: state === 'empty' ? 0 : 1, inStock: state !== 'empty' },
        ],
      }),
    );
    await page.goto('/cart');
    await expect(
      page.getByText(
        state === 'deleted'
          ? 'Produto indisponível'
          : state === 'empty'
            ? 'Sem estoque'
            : 'Apenas 1 em estoque',
        { exact: true },
      ),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Continuar para o pedido' })).toBeDisabled();
    await page.getByRole('button', { name: /^Remover / }).click();
    await expect(page.getByText('Seu carrinho está vazio', { exact: true })).toBeVisible();
  });
}
