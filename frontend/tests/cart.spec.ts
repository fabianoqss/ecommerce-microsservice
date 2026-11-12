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

test.beforeEach(async ({ page }) => {
  await page.route('**/bff/me', (route) => route.fulfill({ json: user }));
  await page.route('**/api/product/**', (route) => route.fulfill({ json: product }));
  await page.route('**/api/inventory**', (route) =>
    route.fulfill({
      json: [{ skuCode: product.id, quantity: 10, inStock: true }],
    }),
  );
});

test('visitor does not see the cart and starts login when adding a product', async ({ page }) => {
  await page.route('**/bff/me', (route) => route.fulfill({ status: 401, json: {} }));
  await page.route('**/oauth2/authorization/keycloak', (route) =>
    route.fulfill({ contentType: 'text/html', body: '<p>Login</p>' }),
  );
  await page.goto('/catalog/' + product.id);
  await expect(page.getByRole('link', { name: /cart/i })).toHaveCount(0);
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page).toHaveURL('/oauth2/authorization/keycloak');
});

test('adds a product, shows a toast and preserves the badge after reloading', async ({ page }) => {
  await page.goto('/catalog/' + product.id);
  const cart = page.getByRole('link', { name: 'Cart, 0 items', exact: true });
  await expect(cart).toBeVisible();
  await expect(cart).toHaveText('');
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByRole('link', { name: 'Cart, 1 item', exact: true })).toHaveText('1');
  await expect(page.getByRole('alert')).toContainText('Product added to cart');
  await expect(page.getByRole('link', { name: 'View cart', exact: true })).toHaveAttribute(
    'href',
    '/cart',
  );
  await expect(page).toHaveURL('/catalog/' + product.id);
  await page.reload();
  await expect(page.getByRole('link', { name: 'Cart, 1 item', exact: true })).toHaveText('1');
  expect(await page.evaluate(() => localStorage.getItem('cart:ana'))).toBe(
    '[{"productId":"670000000000000000000001","quantity":1}]',
  );
});

test('shows the cart in the mobile menu with an accessible touch target', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/catalog/' + product.id);
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await page.getByRole('button', { name: 'Open menu' }).click();
  const cart = page
    .getByRole('navigation', { name: 'Mobile navigation' })
    .getByRole('link', { name: 'Cart, 1 item', exact: true });
  await expect(cart).toBeVisible();
  await expect(cart).toHaveAttribute('href', '/cart');
  const bounds = await cart.boundingBox();
  expect(bounds?.width).toBeGreaterThanOrEqual(44);
  expect(bounds?.height).toBeGreaterThanOrEqual(44);
});

test('synchronizes the cart between tabs for the same account', async ({ page, context }) => {
  await page.goto('/catalog/' + product.id);
  const second = await context.newPage();
  await second.route('**/bff/me', (route) => route.fulfill({ json: user }));
  await second.route('**/api/product/**', (route) => route.fulfill({ json: product }));
  await second.route('**/api/inventory**', (route) =>
    route.fulfill({ json: [{ skuCode: product.id, quantity: 10, inStock: true }] }),
  );
  await second.goto('/catalog/' + product.id);
  await expect(second.getByRole('link', { name: 'Cart, 0 items', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(second.getByRole('link', { name: 'Cart, 1 item', exact: true })).toHaveText('1');
});

test('another account does not see the previous cart', async ({ page }) => {
  await page.goto('/catalog/' + product.id);
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByRole('link', { name: 'Cart, 1 item', exact: true })).toBeVisible();
  await page.route('**/bff/me', (route) =>
    route.fulfill({ json: { ...user, sub: 'bia', name: 'Bia' } }),
  );
  await page.reload();
  await expect(page.getByRole('link', { name: 'Cart, 0 items', exact: true })).toBeVisible();
});

test('reviews two products, changes quantity and removes with updated subtotal and badge', async ({
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
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await page.goto('/catalog/' + mouse.id);
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await page.getByRole('link', { name: 'Cart, 2 items', exact: true }).click();
  await expect(page).toHaveURL('/cart');
  await expect(page).toHaveTitle('Cart | DS Catalog');
  const summary = page.getByRole('complementary', { name: 'Order summary' });
  await expect(summary).toContainText('R$449.90');
  const quantity = page.getByRole('spinbutton', {
    name: 'Quantity for TKL Mechanical Keyboard',
    exact: true,
  });
  await quantity.fill('2');
  await quantity.press('Tab');
  await expect(page.getByRole('link', { name: 'Cart, 3 items', exact: true })).toHaveText('3');
  await expect(summary).toContainText('R$799.80');
  await page.getByRole('button', { name: 'Remove Mouse', exact: true }).click();
  await expect(page.getByRole('article', { name: 'Mouse', exact: true })).toHaveCount(0);
  await expect(summary).toContainText('R$699.80');
  await expect(page.getByRole('link', { name: 'Cart, 2 items', exact: true })).toHaveText('2');
  await expect(page.getByRole('button', { name: 'Checkout' })).toBeEnabled();
});

test('removes one of two products while keeping the other line visible without reloading data', async ({
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
  const summary = page.getByRole('complementary', { name: 'Order summary' });
  await expect(remaining).toBeVisible();
  await expect(summary).toContainText('R$549.90');
  expect(requests).toHaveLength(3);
  const initialRequests = [...requests];
  await page.getByRole('button', { name: 'Remove TKL Mechanical Keyboard', exact: true }).click();
  await expect(page.getByRole('article', { name: product.name, exact: true })).toHaveCount(0);
  await expect(remaining).toBeVisible();
  await expect(remaining.getByRole('spinbutton')).toHaveValue('2');
  await expect(summary).toContainText('R$200.00');
  await expect(page.getByRole('button', { name: 'Checkout' })).toBeEnabled();
  expect(requests).toEqual(initialRequests);
});

test('empty cart offers the catalog without redirecting', async ({ page }) => {
  await page.goto('/cart');
  await expect(page.getByText('Your cart is empty', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Browse catalog', exact: true })).toHaveAttribute(
    'href',
    '/catalog',
  );
  await expect(page).toHaveURL('/cart');
});

test('direct cart access requires login', async ({ page }) => {
  await page.route('**/bff/me', (route) => route.fulfill({ status: 401, json: {} }));
  await page.route('**/oauth2/authorization/keycloak', (route) =>
    route.fulfill({ contentType: 'text/html', body: '<p>Login</p>' }),
  );
  await page.goto('/cart');
  await expect(page).toHaveURL('/oauth2/authorization/keycloak');
});

for (const state of ['deleted', 'empty', 'insufficient'] as const) {
  test(`blocks checkout with a ${state} product and allows removal`, async ({ page }) => {
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
          ? 'Product unavailable'
          : state === 'empty'
            ? 'Out of stock'
            : 'Only 1 in stock',
        { exact: true },
      ),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Checkout' })).toBeDisabled();
    await page.getByRole('button', { name: /^Remove / }).click();
    await expect(page.getByText('Your cart is empty', { exact: true })).toBeVisible();
  });
}
