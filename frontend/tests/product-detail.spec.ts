import { expect, test } from '@playwright/test';

const product = {
  id: '670000000000000000000001',
  name: 'TKL Mechanical Keyboard',
  description: 'Tenkeyless mechanical keyboard.\nWith backlit keys.',
  price: 349.9,
  category: 'Peripherals',
  imageUrl: null,
  version: 0,
};
const detailUrl = '/catalog/' + product.id;

test.beforeEach(async ({ page }) => {
  await page.route('**/api/inventory**', async (route) => {
    const skus = new URL(route.request().url()).searchParams.getAll('skuCode');
    await route.fulfill({
      json: skus.map((skuCode) => ({ skuCode, quantity: 10, inStock: true })),
    });
  });
  await page.route('**/api/product**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/product/categories') {
      await route.fulfill({ json: ['Peripherals'] });
    } else if (url.pathname === '/api/product') {
      await route.fulfill({
        json: {
          content: [product],
          page: Number(url.searchParams.get('page') ?? 0),
          size: 8,
          totalElements: 20,
          totalPages: 3,
        },
      });
    } else if (url.pathname === '/api/product/' + product.id) {
      await route.fulfill({ json: product });
    } else {
      await route.fulfill({
        status: 404,
        json: { status: 404, title: 'Not found', detail: 'The product does not exist.' },
      });
    }
  });
  await page.route('**/bff/me', async (route) => {
    await route.fulfill({ status: 401, json: { status: 401, title: 'Unauthenticated' } });
  });
});

test('opens from the card and returns with page, name and category preserved', async ({ page }) => {
  const query = '?page=2&name=Keyboard&category=Peripherals';
  await page.goto('/catalog' + query);
  const card = page.getByRole('list', { name: 'Products' }).getByRole('link');
  await expect(card).toHaveAttribute('href', detailUrl + query);
  await card.focus();
  await expect(card).toBeFocused();
  await card.press('Enter');
  await expect(page).toHaveURL(detailUrl + query);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(product.name);
  await expect(page).toHaveTitle(product.name + ' | DS Catalog');
  await expect(page.getByRole('link', { name: 'CATALOG', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(page.getByTestId('price-integer')).toHaveText('349');
  await expect(page.getByTestId('price-decimal')).toHaveText('.90');
  await expect(page.getByRole('heading', { name: 'Product description' })).toBeVisible();
  await expect(page.getByText(product.description)).toBeVisible();
  await page.getByRole('link', { name: 'BACK', exact: true }).click();
  await expect(page).toHaveURL('/catalog' + query);
  await expect(page.getByRole('searchbox', { name: 'Product name' })).toHaveValue('Keyboard');
  await expect(page.getByRole('combobox', { name: 'Category' })).toHaveValue('Peripherals');
  await expect(page.getByRole('button', { name: 'Page 2', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(page).toHaveTitle('Catalog | DS Catalog');
});

test('opens directly and returns to the catalog without relying on history', async ({ page }) => {
  await page.goto(detailUrl);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(product.name);
  await expect(page).toHaveTitle(product.name + ' | DS Catalog');
  await page.getByRole('link', { name: 'BACK', exact: true }).click();
  await expect(page).toHaveURL('/catalog');
  await expect(page.getByRole('searchbox', { name: 'Product name' })).toHaveValue('');
});

test('shows a skeleton and temporary title while loading', async ({ page }) => {
  let release: () => void = () => undefined;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route('**/api/product/' + product.id, async (route) => {
    await pending;
    await route.fulfill({ json: product });
  });
  await page.goto(detailUrl);
  try {
    await expect(page).toHaveTitle('Product | DS Catalog');
    await expect(page.getByRole('status')).toHaveText('Loading product.');
    await expect(page.locator('p-skeleton')).toHaveCount(4);
  } finally {
    release();
  }
  await expect(page).toHaveTitle(product.name + ' | DS Catalog');
  await expect(page.locator('p-skeleton')).toHaveCount(0);
});

test('shows 404 and allows returning to the catalog', async ({ page }) => {
  await page.goto('/catalog/nonexistent');
  await expect(page.getByRole('heading', { name: 'Product not found.' })).toBeVisible();
  await expect(page).toHaveTitle('Product | DS Catalog');
  await page.getByRole('link', { name: 'Back to catalog', exact: true }).click();
  await expect(page).toHaveURL('/catalog');
});

test('shows ProblemDetail and allows retrying', async ({ page }) => {
  let unavailable = true;
  await page.route('**/api/product/' + product.id, async (route) => {
    await route.fulfill(
      unavailable
        ? { status: 503, json: { status: 503, detail: 'Service unavailable.' } }
        : { json: product },
    );
  });
  await page.goto(detailUrl);
  await expect(page.getByRole('alert')).toContainText('Service unavailable.');
  unavailable = false;
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(product.name);
});

test('uses two columns on desktop and one on mobile without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(detailUrl);
  const name = page.getByRole('heading', { level: 1 });
  const description = page.getByRole('heading', { name: 'Product description' });
  await expect(name).toBeVisible();
  const desktopName = await name.boundingBox();
  const desktopDescription = await description.boundingBox();
  expect(desktopDescription?.x).toBeGreaterThan((desktopName?.x ?? 0) + 500);
  await page.setViewportSize({ width: 375, height: 812 });
  await expect(name).toBeVisible();
  const mobileName = await name.boundingBox();
  const mobileDescription = await description.boundingBox();
  expect(mobileDescription?.y).toBeGreaterThan(mobileName?.y ?? 0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
});
