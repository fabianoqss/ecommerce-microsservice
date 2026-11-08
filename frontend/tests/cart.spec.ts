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
