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
const order = {
  id: 'd715a7c9-d942-4c65-92eb-704c797dc6ee',
  status: 'PENDING',
  totalValue: 349.9,
  orderTime: '2026-11-08T10:30:00',
  items: [
    {
      productId: product.id,
      productName: product.name,
      quantity: 1,
      priceAtPurchase: 349.9,
    },
  ],
};

test.beforeEach(async ({ page }) => {
  await page.route('**/bff/me', (route) => route.fulfill({ json: user }));
  await page.route('**/api/**', (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/product/' + product.id) {
      return route.fulfill({ json: product });
    }
    if (url.pathname === '/api/inventory') {
      return route.fulfill({
        json: [{ skuCode: product.id, quantity: 10, inStock: true }],
      });
    }
    return route.fulfill({ status: 404, json: { detail: 'Recurso não encontrado.' } });
  });
});

test('revisa itens sem editar e cria um único pedido limpando o carrinho após 201', async ({
  page,
}) => {
  let posts = 0;
  let releaseResponse: () => void = () => undefined;
  const responseReady = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  await page.route('**/api/order', async (route) => {
    posts += 1;
    await responseReady;
    await route.fulfill({ status: 201, json: order });
  });

  await page.goto('/catalog/' + product.id);
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await page.getByRole('link', { name: 'Carrinho, 1 item', exact: true }).click();
  await page.getByRole('button', { name: 'Continuar para o pedido' }).click();
  await expect(page).toHaveURL('/checkout');
  await expect(page).toHaveTitle('Pedido | DS Catalog');
  const items = page.getByRole('list', { name: 'Produtos no pedido' });
  await expect(items).toContainText(product.name);
  await expect(items).toContainText('Quantidade: 1');
  await expect(items.getByRole('spinbutton')).toHaveCount(0);
  await expect(page.getByRole('complementary', { name: 'Resumo do pedido' })).toContainText(
    'R$ 349,90',
  );
  await expect(page.getByRole('link', { name: 'Voltar ao carrinho' })).toHaveAttribute(
    'href',
    '/cart',
  );
  const submit = page.getByRole('button', { name: 'Finalizar pedido', exact: true });
  const pending = page.waitForRequest(
    (request) => new URL(request.url()).pathname === '/api/order' && request.method() === 'POST',
  );
  await submit.dblclick({ force: true });
  const request = await pending;
  expect(request.postDataJSON()).toEqual({ items: [{ productId: product.id, quantity: 1 }] });
  expect(request.headers()['idempotency-key']).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
  );
  await expect(submit).toBeDisabled();
  await expect(page.getByRole('link', { name: 'Carrinho, 1 item', exact: true })).toHaveText('1');
  expect(posts).toBe(1);
  releaseResponse();
  await expect(page.getByRole('link', { name: 'Carrinho, 0 itens', exact: true })).toHaveText('');
  await expect.poll(() => page.evaluate(() => localStorage.getItem('cart:ana'))).toBe('[]');
  expect(posts).toBe(1);
});

test('mostra conflito, mantém badge e reutiliza chave e itens ao voltar ao checkout', async ({
  page,
}) => {
  const keys: (string | undefined)[] = [];
  const bodies: unknown[] = [];
  await page.route('**/api/order', (route) => {
    keys.push(route.request().headers()['idempotency-key']);
    bodies.push(route.request().postDataJSON());
    return route.fulfill({
      status: 409,
      json: { status: 409, title: 'Conflito', detail: 'Estoque insuficiente para este pedido.' },
    });
  });
  await page.goto('/catalog/' + product.id);
  await page.getByRole('button', { name: 'Adicionar ao carrinho' }).click();
  await page.goto('/checkout');
  const submit = page.getByRole('button', { name: 'Finalizar pedido', exact: true });
  await submit.click();
  await expect(page.getByRole('alert')).toContainText('Estoque insuficiente para este pedido.');
  await expect(page).toHaveURL('/checkout');
  await expect(page.getByRole('link', { name: 'Carrinho, 1 item', exact: true })).toHaveText('1');
  await expect(submit).toBeEnabled();
  await page.getByRole('link', { name: 'Voltar ao carrinho', exact: true }).click();
  await expect(page).toHaveURL('/cart');
  await page.getByRole('button', { name: 'Continuar para o pedido', exact: true }).click();
  await expect(page).toHaveURL('/checkout');
  await submit.click();
  await expect.poll(() => keys.length).toBe(2);
  await expect(page.getByRole('alert')).toContainText('Estoque insuficiente para este pedido.');
  expect(keys[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  expect(keys[1]).toBe(keys[0]);
  expect(bodies).toEqual([
    { items: [{ productId: product.id, quantity: 1 }] },
    { items: [{ productId: product.id, quantity: 1 }] },
  ]);
  expect(await page.evaluate(() => localStorage.getItem('cart:ana'))).toBe(
    '[{"productId":"670000000000000000000001","quantity":1}]',
  );
});
