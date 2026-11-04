import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/bff/me', async (route) => {
    await route.fulfill({
      json: {
        sub: 'admin-1',
        name: 'Ana Maria Silva',
        email: 'ana@example.com',
        roles: ['CUSTOMER', 'ADMIN'],
      },
    });
  });
  await page.route('**/api/product**', async (route) => {
    await route.fulfill({
      json: { content: [], page: 0, size: 8, totalElements: 0, totalPages: 0 },
    });
  });
  await page.route('**/api/product/categories', async (route) => {
    await route.fulfill({ json: [] });
  });
});

test('admin chega a Produtos com menu encostado à esquerda no desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/admin');

  await expect(page).toHaveURL(/\/admin\/products$/);
  await expect(page).toHaveTitle('Produtos | DS Catalog');
  const menu = page.getByRole('navigation', { name: 'Administração', exact: true });
  await expect(menu).toBeVisible();
  await expect(menu.getByRole('link', { name: 'Produtos', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(menu.getByRole('link', { disabled: false })).toHaveCount(1);
  await expect(menu.getByRole('button')).toHaveCount(0);
  await expect(menu.getByRole('link', { disabled: true })).toHaveText(['Categorias', 'Usuários']);
  const bounds = await menu.boundingBox();
  expect(bounds?.x).toBe(0);
  expect(bounds?.y).toBe(70);
  expect(bounds?.width).toBe(304);
  expect(bounds?.height).toBe(830);
  const products = await menu.getByRole('link', { name: 'Produtos', exact: true }).boundingBox();
  expect(products?.height).toBe(83);
  expect(products?.width).toBe(304);
});

test('CUSTOMER é redirecionado de /admin para a home', async ({ page }) => {
  await page.route('**/bff/me', async (route) => {
    await route.fulfill({
      json: {
        sub: 'customer-1',
        name: 'Ana Silva',
        email: 'ana@example.com',
        roles: ['CUSTOMER'],
      },
    });
  });
  await page.goto('/admin');

  await expect(page).toHaveURL('/');
  await expect(page).toHaveTitle('DS Catalog');
  await expect(page.getByRole('navigation', { name: 'Administração', exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Conheça o melhor catálogo de produtos',
  );
});

test('menu móvel tem três itens na mesma linha sem transbordar', async ({ page }) => {
  await page.setViewportSize({ width: 414, height: 896 });
  await page.goto('/admin');
  const menu = page.getByRole('navigation', { name: 'Administração', exact: true });
  await expect(menu).toBeVisible();
  const bounds = await menu.boundingBox();
  expect(bounds?.x).toBe(0);
  expect(bounds?.y).toBe(50);
  expect(bounds?.width).toBe(414);
  expect(bounds?.height).toBe(88);
  const items = await menu.getByRole('listitem').evaluateAll((elements) =>
    elements.map((element) => {
      const rect = element.getBoundingClientRect();
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
    }),
  );
  expect(items).toHaveLength(3);
  expect(items[0]?.x).toBe(20);
  expect(new Set(items.map((item) => item.y)).size).toBe(1);
  for (const item of items) {
    expect(item.height).toBe(49);
    expect(item.width).toBeCloseTo(332 / 3, 1);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(414);
});

for (const viewport of [
  { width: 1600, height: 900, padding: '40px', x: 80 },
  { width: 414, height: 896, padding: '20px', x: 0 },
]) {
  test(`home e catálogo preservam contêiner em ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    for (const route of [
      { url: '/', title: 'DS Catalog' },
      { url: '/catalog', title: 'Catálogo | DS Catalog' },
    ]) {
      await page.goto(route.url);
      await expect(page).toHaveTitle(route.title);
      const container = page.locator('app-public-layout > div');
      await expect(container).toBeVisible();
      await expect(container).toHaveCSS('padding-left', viewport.padding);
      await expect(container).toHaveCSS('padding-right', viewport.padding);
      await expect(container).toHaveCSS('padding-top', '40px');
      await expect(container).toHaveCSS('padding-bottom', '40px');
      const bounds = await container.boundingBox();
      expect(bounds?.x).toBe(viewport.x);
      expect(bounds?.width).toBe(Math.min(viewport.width, 1440));
      await expect(
        page.getByRole('navigation', { name: 'Administração', exact: true }),
      ).toHaveCount(0);
    }
  });
}

test('menu acompanha o fundo escuro e mantém Produtos identificado', async ({ page }) => {
  await page.goto('/admin/products');
  const menu = page.getByRole('navigation', { name: 'Administração', exact: true });
  await expect(menu).toBeVisible();
  const lightBackground = await menu.evaluate(
    (element) => getComputedStyle(element).backgroundColor,
  );
  await page.evaluate(() => document.documentElement.classList.add('p-dark'));
  await expect
    .poll(() => menu.evaluate((element) => getComputedStyle(element).backgroundColor))
    .not.toBe(lightBackground);
  await expect(menu.getByRole('link', { name: 'Produtos', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(menu.getByText('Categorias', { exact: true })).toBeVisible();
  await expect(menu.getByText('Usuários', { exact: true })).toBeVisible();
});
