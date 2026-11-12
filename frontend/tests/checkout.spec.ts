import { expect, test } from '@playwright/test';

const product = {
  id: '670000000000000000000001',
  name: 'TKL Mechanical Keyboard',
  description: 'Compact keyboard.',
  price: 349.9,
  category: 'Peripherals',
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
    if (url.pathname === '/api/order/' + order.id) {
      return route.fulfill({ json: order });
    }
    return route.fulfill({ status: 404, json: { detail: 'Resource not found.' } });
  });
});

test('reviews items without editing and creates one order, clearing the cart after 201', async ({
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
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await page.getByRole('link', { name: 'Cart, 1 item', exact: true }).click();
  await page.getByRole('button', { name: 'Checkout' }).click();
  await expect(page).toHaveURL('/checkout');
  await expect(page).toHaveTitle('Order | DS Catalog');
  const items = page.getByRole('list', { name: 'Products in your order' });
  await expect(items).toContainText(product.name);
  await expect(items).toContainText('Quantity: 1');
  await expect(items.getByRole('spinbutton')).toHaveCount(0);
  await expect(page.getByRole('complementary', { name: 'Order summary' })).toContainText(
    'R$349.90',
  );
  await expect(page.getByRole('link', { name: 'Back to cart' })).toHaveAttribute('href', '/cart');
  const submit = page.getByRole('button', { name: 'Place order', exact: true });
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
  await expect(page.getByRole('link', { name: 'Cart, 1 item', exact: true })).toHaveText('1');
  expect(posts).toBe(1);
  releaseResponse();
  await expect(page).toHaveURL('/orders/' + order.id + '/confirmation');
  await expect(page).toHaveTitle('Order confirmed | DS Catalog');
  await expect(page.getByRole('heading', { name: 'Order confirmed!', exact: true })).toBeVisible();
  await expect(page.getByText(order.id, { exact: true })).toBeVisible();
  await expect(page.getByText('Processing', { exact: true })).toBeVisible();
  await expect(page.getByText('We are confirming your stock reservation.')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Products in your order' })).toContainText(
    product.name,
  );
  await expect(page.getByRole('list', { name: 'Products in your order' })).toContainText(
    'Quantity: 1',
  );
  await expect(page.getByRole('list', { name: 'Products in your order' })).toContainText(
    'Unit price: R$349.90',
  );
  await expect(page.getByRole('complementary', { name: 'Order summary' })).toContainText(
    'R$349.90',
  );
  await expect(page.getByText('Nov 8, 2026, 10:30 AM', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Continue shopping', exact: true })).toHaveAttribute(
    'href',
    '/catalog',
  );
  await expect(page.getByRole('link', { name: 'Cart, 0 items', exact: true })).toHaveText('');
  await expect(
    page.getByRole('link', { name: 'Cart, 0 items', exact: true }).getByText('1', { exact: true }),
  ).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => localStorage.getItem('cart:ana'))).toBe('[]');
  expect(posts).toBe(1);
});

test('shows order not found on direct navigation when the API returns 404', async ({ page }) => {
  await page.route('**/api/order/' + order.id, (route) =>
    route.fulfill({
      status: 404,
      json: { status: 404, title: 'Not found', detail: 'Order not found.' },
    }),
  );
  await page.goto('/orders/' + order.id + '/confirmation');
  await expect(page).toHaveURL('/orders/' + order.id + '/confirmation');
  await expect(page.getByText('Order not found.', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Order confirmed!', exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Continue shopping', exact: true })).toHaveAttribute(
    'href',
    '/catalog',
  );
});

test('shows a conflict, preserves the badge and reuses the key and items on returning to checkout', async ({
  page,
}) => {
  const keys: (string | undefined)[] = [];
  const bodies: unknown[] = [];
  await page.route('**/api/order', (route) => {
    keys.push(route.request().headers()['idempotency-key']);
    bodies.push(route.request().postDataJSON());
    return route.fulfill({
      status: 409,
      json: { status: 409, title: 'Conflict', detail: 'Insufficient stock for this order.' },
    });
  });
  await page.goto('/catalog/' + product.id);
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await page.goto('/checkout');
  const submit = page.getByRole('button', { name: 'Place order', exact: true });
  await submit.click();
  await expect(page.getByRole('alert')).toContainText('Insufficient stock for this order.');
  await expect(page).toHaveURL('/checkout');
  await expect(page.getByRole('link', { name: 'Cart, 1 item', exact: true })).toHaveText('1');
  await expect(submit).toBeEnabled();
  await page.getByRole('link', { name: 'Back to cart', exact: true }).click();
  await expect(page).toHaveURL('/cart');
  await page.getByRole('button', { name: 'Checkout', exact: true }).click();
  await expect(page).toHaveURL('/checkout');
  await submit.click();
  await expect.poll(() => keys.length).toBe(2);
  await expect(page.getByRole('alert')).toContainText('Insufficient stock for this order.');
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
