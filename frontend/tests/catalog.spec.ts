import { expect, test } from '@playwright/test';

const products = Array.from({ length: 8 }, (_, index) => ({
  id: String(index + 1),
  name: index === 0 ? 'TKL Mechanical Keyboard' : `Product ${index + 1}`,
  description: 'Catalog product',
  price: index === 0 ? 1999 : 89.9,
  category: 'Peripherals',
  imageUrl: null,
  version: 0,
}));

const productPage = { content: products, page: 0, size: 8, totalElements: 280, totalPages: 35 };

test.beforeEach(async ({ page }) => {
  await page.route('**/api/product**', async (route) => {
    const url = new URL(route.request().url());
    await route.fulfill({
      json: { ...productPage, page: Number(url.searchParams.get('page') ?? 0) },
    });
  });
  await page.route('**/api/product/categories', async (route) => {
    await route.fulfill({ json: ['Accessories', 'Storage', 'Peripherals'] });
  });
  await page.route('**/bff/me', async (route) => {
    await route.fulfill({ status: 401, json: { status: 401, title: 'Unauthenticated' } });
  });
});

test('lists eight cards with prices and an active link', async ({ page }) => {
  await page.goto('/catalog');
  await expect(page).toHaveTitle('Catalog | DS Catalog');
  await expect(page.getByRole('heading', { level: 1, name: 'Product catalog' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Products' }).getByRole('article')).toHaveCount(8);
  await expect(page.getByRole('heading', { name: 'TKL Mechanical Keyboard' })).toBeVisible();
  await expect(
    page.getByRole('list', { name: 'Products' }).getByRole('link').first(),
  ).toHaveAttribute('href', '/catalog/1');
  await expect(page.getByTestId('price-integer').first()).toHaveText('1,999');
  await expect(page.getByTestId('price-decimal').first()).toHaveText('.00');
  await expect(page.getByRole('link', { name: 'CATALOG' })).toHaveAttribute('aria-current', 'page');
  const navigation = page.getByRole('navigation', { name: 'Pagination' });
  await expect(
    navigation.getByRole('button', { name: 'Previous page', exact: true }),
  ).toBeDisabled();
  await expect(navigation.getByRole('button', { name: /^Page \d+$/ })).toHaveCount(10);
});

test('changes the page through the URL, scrolls to the list and allows going back', async ({
  page,
}) => {
  await page.goto('/catalog');
  await page.getByRole('button', { name: 'Page 2', exact: true }).click();
  await expect(page).toHaveURL(/\/catalog\?page=2$/);
  await expect(page.getByRole('button', { name: 'Page 2', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect
    .poll(() =>
      page
        .getByRole('list', { name: 'Products' })
        .evaluate((list) => list.getBoundingClientRect().top),
    )
    .toBeLessThan(100);
  await page.goBack();
  await expect(page).toHaveURL(/\/catalog$/);
  await expect(page.getByRole('button', { name: 'Page 1', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
});

test('sends a trimmed name after debounce and returns to the first page', async ({ page }) => {
  await page.goto('/catalog?page=3');
  const request = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === '/api/product' && url.searchParams.get('name') === 'Keyboard';
  });
  await page.getByRole('searchbox', { name: 'Product name' }).fill(' Keyboard ');
  const url = new URL((await request).url());
  expect(url.searchParams.get('page')).toBe('0');
  expect(url.searchParams.get('size')).toBe('8');
  await expect(page).toHaveURL(/\/catalog\?name=Keyboard$/);
});

test('changes category, preserves the name and restores filters on return', async ({ page }) => {
  await page.goto('/catalog?page=3&name=Keyboard');
  await expect(page.getByRole('combobox', { name: 'Category' })).toBeEnabled();
  const request = page.waitForRequest(
    (request) => new URL(request.url()).searchParams.get('category') === 'Peripherals',
  );
  await page.getByRole('combobox', { name: 'Category' }).selectOption('Peripherals');
  const url = new URL((await request).url());
  expect(url.searchParams.get('page')).toBe('0');
  expect(url.searchParams.get('name')).toBe('Keyboard');
  await expect(page).toHaveURL(/category=Peripherals/);
  await page.goBack();
  await expect(page.getByRole('combobox', { name: 'Category' })).toHaveValue('');
  await expect(page.getByRole('searchbox', { name: 'Product name' })).toHaveValue('Keyboard');
  await expect(page.getByRole('button', { name: 'Page 3', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
});

test('shows an empty state and removes filters and page when clearing', async ({ page }) => {
  await page.route('**/api/product?**', async (route) => {
    const filtered = new URL(route.request().url()).searchParams.has('name');
    await route.fulfill({
      json: filtered
        ? { ...productPage, content: [], totalElements: 0, totalPages: 0 }
        : productPage,
    });
  });
  await page.goto('/catalog?page=3&name=Nonexistent&category=Peripherals');
  await expect(page.getByText('No products found.', { exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Pagination' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Clear filters', exact: true }).click();
  await expect(page).toHaveURL(/\/catalog$/);
  await expect(page.getByRole('article')).toHaveCount(8);
  await expect(page.getByRole('searchbox', { name: 'Product name' })).toHaveValue('');
});

test('shows ProblemDetail and reloads on retry', async ({ page }) => {
  let unavailable = true;
  await page.route('**/api/product?**', async (route) => {
    await route.fulfill(
      unavailable
        ? {
            status: 503,
            json: {
              status: 503,
              title: 'Unavailable',
              detail: 'Product service unavailable.',
            },
          }
        : { json: productPage },
    );
  });
  await page.goto('/catalog');
  await expect(page.getByRole('alert')).toBeVisible();
  unavailable = false;
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByRole('article')).toHaveCount(8);
});

test('continues fetching products when categories are unavailable', async ({ page }) => {
  await page.route('**/api/product/categories', async (route) => {
    await route.fulfill({ status: 404, json: { status: 404 } });
  });
  await page.goto('/catalog');
  await expect(page.getByRole('article')).toHaveCount(8);
  await expect(page.getByRole('combobox', { name: 'Category' })).toBeDisabled();
  await expect(page.getByRole('searchbox', { name: 'Product name' })).toBeEnabled();
});

test('adapts search, grid and pagination for mobile without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/catalog?page=18');
  await expect(page.getByRole('article')).toHaveCount(8);
  const navigation = page.getByRole('navigation', { name: 'Pagination' });
  expect(await navigation.getByRole('button', { name: /^Page \d+$/ }).count()).toBeLessThanOrEqual(
    6,
  );
  await expect(page.getByRole('button', { name: 'Page 18', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
  const cards = await page
    .getByRole('article')
    .evaluateAll((cards) => cards.map((card) => card.getBoundingClientRect().x));
  expect(new Set(cards).size).toBe(1);
});
