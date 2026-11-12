import { expect, Page, test } from '@playwright/test';

const products = Array.from({ length: 4 }, (_, index) => ({
  id: String(index + 1),
  name: 'Computer with a long name to check truncation ' + (index + 1),
  description: 'Computer for work',
  price: 2779,
  category: 'Computers and accessories with a long category name',
  imageUrl: null,
  version: 0,
}));
const productPage = { content: products, page: 0, size: 4, totalElements: 4, totalPages: 1 };
const paginatedProducts = Array.from({ length: 9 }, (_, index) => ({
  ...products[0],
  id: String(index + 1),
  name: index < 6 ? `Keyboard ${index + 1}` : `Monitor ${index + 1}`,
  category: index < 6 ? 'Peripherals' : 'Monitors',
}));

async function mockPaginatedProducts(page: Page) {
  await page.route('**/api/product?**', async (route) => {
    const params = new URL(route.request().url()).searchParams;
    const currentPage = Number(params.get('page'));
    expect(params.get('size')).toBe('4');
    const name = params.get('name')?.toLowerCase() ?? '';
    const category = params.get('category');
    const filtered = paginatedProducts.filter(
      (product) =>
        product.name.toLowerCase().includes(name) && (!category || product.category === category),
    );
    await route.fulfill({
      json: {
        content: filtered.slice(currentPage * 4, currentPage * 4 + 4),
        page: currentPage,
        size: 4,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / 4),
      },
    });
  });
  await page.route('**/api/product/categories', (route) =>
    route.fulfill({ json: ['Peripherals', 'Monitors'] }),
  );
}

test.beforeEach(async ({ page }) => {
  await page.route('https://example.com/**', (route) => route.abort());
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
      json: productPage,
    });
  });
  await page.route('**/api/product/categories', async (route) => {
    await route.fulfill({ json: [] });
  });
  await page.route('**/api/inventory**', async (route) => {
    const request = route.request();
    if (request.method() === 'GET') {
      const codes = new URL(request.url()).searchParams.getAll('skuCode');
      return route.fulfill({
        json: codes.map((skuCode) => ({ skuCode, quantity: 12, inStock: true })),
      });
    }
    const body: { skuCode?: string; quantity: number } = request.postDataJSON();
    await route.fulfill({
      status: request.method() === 'POST' ? 201 : 200,
      json: {
        skuCode: body.skuCode ?? new URL(request.url()).pathname.split('/').at(-1),
        quantity: body.quantity,
        inStock: body.quantity > 0,
      },
    });
  });
});

test('shows action and unavailable cursors on administrative buttons', async ({ page }) => {
  await mockPaginatedProducts(page);
  await page.goto('/admin/products');

  const pageNumber = page.getByRole('button', { name: 'Page 2', exact: true });
  const previousPage = page.getByRole('button', { name: 'Previous page', exact: true });
  const edit = page.getByRole('button', { name: 'Edit Keyboard 1', exact: true });

  await expect(pageNumber).toBeEnabled();
  await expect(pageNumber).toHaveCSS('cursor', 'pointer');
  await expect(previousPage).toBeDisabled();
  await expect(previousPage).toHaveCSS('cursor', 'not-allowed');
  await expect(edit).toBeEnabled();
  await expect(edit).toHaveCSS('cursor', 'pointer');
});

test('paginates the administrative list through the URL and shows page 2 cards', async ({
  page,
}) => {
  await mockPaginatedProducts(page);
  await page.goto('/admin/products?extra=1');
  const list = page.getByRole('list', { name: 'Products', exact: true });
  await expect(list.getByRole('heading')).toHaveText([
    'Keyboard 1',
    'Keyboard 2',
    'Keyboard 3',
    'Keyboard 4',
  ]);
  await page.getByRole('button', { name: 'Page 2', exact: true }).click();
  await expect(page).toHaveURL('/admin/products?extra=1&page=2');
  await expect(list.getByRole('article')).toHaveCount(4);
  await expect(list.getByRole('heading')).toHaveText([
    'Keyboard 5',
    'Keyboard 6',
    'Monitor 7',
    'Monitor 8',
  ]);
  await expect(page.getByRole('button', { name: 'Page 2', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
});

for (const filter of ['name', 'category', 'clear']) {
  test(`filter ${filter} removes the page from the URL and fetches the first page`, async ({
    page,
  }) => {
    await mockPaginatedProducts(page);
    await page.goto(
      filter === 'clear'
        ? '/admin/products?page=2&name=Keyboard&category=Peripherals&extra=1'
        : '/admin/products?page=2&extra=1',
    );
    await expect(page.getByRole('button', { name: 'Page 2', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );
    if (filter === 'name') {
      await page.getByRole('searchbox', { name: 'Product name', exact: true }).fill('Monitor');
    } else if (filter === 'category') {
      await page.getByRole('combobox', { name: 'Category', exact: true }).selectOption('Monitors');
    } else {
      await page.getByRole('button', { name: 'CLEAR FILTERS', exact: true }).click();
    }
    await expect(page).toHaveURL(
      filter === 'name'
        ? '/admin/products?extra=1&name=Monitor'
        : filter === 'category'
          ? '/admin/products?extra=1&category=Monitors'
          : '/admin/products?extra=1',
    );
    await expect(
      page.getByRole('list', { name: 'Products', exact: true }).getByRole('heading'),
    ).toHaveText(
      filter === 'clear'
        ? ['Keyboard 1', 'Keyboard 2', 'Keyboard 3', 'Keyboard 4']
        : ['Monitor 7', 'Monitor 8', 'Monitor 9'],
    );
  });
}

for (const action of ['CANCEL', 'SAVE']) {
  test(`editing from page 2 and choosing ${action} returns to the same page`, async ({ page }) => {
    await mockPaginatedProducts(page);
    const product = paginatedProducts[4];
    await page.route('**/api/product/5', async (route) => {
      if (route.request().method() === 'GET') return route.fulfill({ json: product });
      expect(route.request().method()).toBe('PUT');
      await route.fulfill({ json: { ...product, version: 1 } });
    });
    await page.goto('/admin/products?extra=1');
    await page.getByRole('button', { name: 'Page 2', exact: true }).click();
    await expect(page).toHaveURL('/admin/products?extra=1&page=2');
    await page.getByRole('button', { name: 'Edit Keyboard 5', exact: true }).click();
    await expect(page).toHaveURL('/admin/products/5/edit?extra=1&page=2');
    await expect(page.getByLabel('Product name', { exact: true })).toHaveValue('Keyboard 5');
    await page.getByRole('button', { name: action, exact: true }).click();
    await expect(page).toHaveURL('/admin/products?extra=1&page=2');
    await expect(
      page.getByRole('list', { name: 'Products', exact: true }).getByRole('heading'),
    ).toHaveText(['Keyboard 5', 'Keyboard 6', 'Monitor 7', 'Monitor 8']);
    await expect(page.getByRole('button', { name: 'Page 2', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });
}

test('admin reaches Products with the menu flush left on desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/admin');

  await expect(page).toHaveURL(/\/admin\/products$/);
  await expect(page).toHaveTitle('Products | DS Catalog');
  const menu = page.getByRole('navigation', { name: 'Administration', exact: true });
  await expect(menu).toBeVisible();
  await expect(menu.getByRole('link', { name: 'Products', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(menu.getByRole('link', { disabled: false })).toHaveCount(1);
  await expect(menu.getByRole('button')).toHaveCount(0);
  await expect(menu.getByRole('link', { disabled: true })).toHaveText(['Categories', 'Users']);
  const bounds = await menu.boundingBox();
  expect(bounds?.x).toBe(0);
  expect(bounds?.y).toBe(70);
  expect(bounds?.width).toBe(304);
  expect(bounds?.height).toBeGreaterThanOrEqual(830);
  const products = await menu.getByRole('link', { name: 'Products', exact: true }).boundingBox();
  expect(products?.height).toBe(83);
  expect(products?.width).toBe(304);
});

test('redirects CUSTOMER from /admin to home', async ({ page }) => {
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
  await expect(page.getByRole('navigation', { name: 'Administration', exact: true })).toHaveCount(
    0,
  );
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Explore our product catalog');
});

test('mobile menu has three items on the same row without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 414, height: 896 });
  await page.goto('/admin');
  const menu = page.getByRole('navigation', { name: 'Administration', exact: true });
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
  test(`home and catalog preserve the container at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    for (const route of [
      { url: '/', title: 'DS Catalog' },
      { url: '/catalog', title: 'Catalog | DS Catalog' },
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
        page.getByRole('navigation', { name: 'Administration', exact: true }),
      ).toHaveCount(0);
    }
  });
}

test('menu follows the dark background and keeps Products identified', async ({ page }) => {
  await page.goto('/admin/products');
  const menu = page.getByRole('navigation', { name: 'Administration', exact: true });
  await expect(menu).toBeVisible();
  const lightBackground = await menu.evaluate(
    (element) => getComputedStyle(element).backgroundColor,
  );
  await page.evaluate(() => document.documentElement.classList.add('p-dark'));
  await expect
    .poll(() => menu.evaluate((element) => getComputedStyle(element).backgroundColor))
    .not.toBe(lightBackground);
  await expect(menu.getByRole('link', { name: 'Products', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(menu.getByText('Categories', { exact: true })).toBeVisible();
  await expect(menu.getByText('Users', { exact: true })).toBeVisible();
});

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 1024, height: 900 },
]) {
  test(`toolbar aligns the button and search on desktop at ${viewport.width}px`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await page.goto('/admin/products');
    const button = page.getByRole('link', { name: 'ADD', exact: true });
    const search = page.locator('app-admin-products app-product-search');
    await expect(button).toBeVisible();
    await expect(search).toBeVisible();
    const buttonBounds = await button.boundingBox();
    const searchBounds = await search.boundingBox();
    if (!buttonBounds || !searchBounds) throw new Error('Toolbar missing');
    expect(buttonBounds.x).toBe(345);
    expect(buttonBounds.y).toBe(96);
    expect(buttonBounds.width).toBe(170);
    expect(buttonBounds.height).toBe(60);
    expect(searchBounds.x - buttonBounds.x - buttonBounds.width).toBe(28);
    expect(searchBounds.y).toBe(buttonBounds.y);
    expect(searchBounds.height).toBe(60);
    expect(searchBounds.width).toBe(viewport.width - 583);
    expect(searchBounds.x + searchBounds.width).toBe(viewport.width - 40);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(viewport.width);
  });
}

for (const viewport of [
  { width: 414, height: 896 },
  { width: 767, height: 1024 },
]) {
  test(`toolbar stacks the button and search on mobile at ${viewport.width}px`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await page.goto('/admin/products');
    const button = page.getByRole('link', { name: 'ADD', exact: true });
    const search = page.locator('app-admin-products app-product-search');
    await expect(button).toBeVisible();
    await expect(search).toBeVisible();
    const buttonBounds = await button.boundingBox();
    const searchBounds = await search.boundingBox();
    if (!buttonBounds || !searchBounds) throw new Error('Toolbar missing');
    expect(buttonBounds.x).toBe(20);
    expect(buttonBounds.y).toBe(158);
    expect(buttonBounds.width).toBe(viewport.width - 40);
    expect(buttonBounds.height).toBe(50);
    expect(searchBounds.x).toBe(buttonBounds.x);
    expect(searchBounds.width).toBe(buttonBounds.width);
    expect(searchBounds.y - buttonBounds.y - buttonBounds.height).toBe(20);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(viewport.width);
  });
}

test('typing a name in administrative search updates the URL', async ({ page }) => {
  await page.goto('/admin/products');
  await page.getByRole('searchbox', { name: 'Product name' }).fill('Keyboard');
  await expect(page).toHaveURL('/admin/products?name=Keyboard');
  await expect(page.getByRole('searchbox', { name: 'Product name' })).toHaveValue('Keyboard');
});

test('cards follow the design geometry at 1440px', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/admin/products');
  const list = page.getByRole('list', { name: 'Products', exact: true });
  const cards = list.getByRole('article');
  await expect(cards).toHaveCount(4);
  const first = await cards.nth(0).boundingBox();
  const second = await cards.nth(1).boundingBox();
  if (!first || !second) throw new Error('Cards missing');
  expect(first).toEqual({ x: 345, y: 199, width: 1055, height: 160 });
  expect(second.y - first.y - first.height).toBe(21);
  for (const [label, offset] of [
    ['Edit', 31],
    ['Delete', 91],
  ] as const) {
    const button = cards.nth(0).getByRole('button', { name: label + ' ' + products[0].name });
    const bounds = await button.boundingBox();
    if (!bounds) throw new Error('Button missing');
    expect(bounds.width).toBe(213);
    expect(bounds.height).toBe(38);
    expect(bounds.x).toBe(first.x + first.width - 17 - 213);
    expect(bounds.y).toBe(first.y + offset);
  }
});

test('cards do not overflow and have side-by-side buttons at 414px', async ({ page }) => {
  await page.setViewportSize({ width: 414, height: 896 });
  await page.goto('/admin/products');
  const cards = page.getByRole('list', { name: 'Products', exact: true }).getByRole('article');
  await expect(cards).toHaveCount(4);
  for (const card of await cards.all()) {
    const bounds = await card.boundingBox();
    const buttons = card.getByRole('button');
    const edit = await buttons.nth(0).boundingBox();
    const remove = await buttons.nth(1).boundingBox();
    if (!bounds || !edit || !remove) throw new Error('Card or buttons missing');
    expect(bounds.x).toBe(20);
    expect(bounds.width).toBe(374);
    expect(edit.y).toBe(remove.y);
    expect(edit.width).toBe(remove.width);
    expect(remove.x).toBeGreaterThan(edit.x);
    expect(remove.x + remove.width).toBeLessThanOrEqual(bounds.x + bounds.width);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(414);
});

test('preserves four cards without skeletons during a delayed filter response', async ({
  page,
}) => {
  await page.goto('/admin/products');
  const list = page.getByRole('list', { name: 'Products', exact: true });
  await expect(list.getByRole('article')).toHaveCount(4);
  let releaseResponse: () => void = () => {
    throw new Error('Pending response not initialized');
  };
  const pendingResponse = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  await page.route('**/api/product?**', async (route) => {
    await pendingResponse;
    await route.fulfill({
      json: {
        ...productPage,
        content: [{ ...products[0], id: 'monitor', name: 'Monitor' }],
        totalElements: 1,
      },
    });
  });
  await list.evaluate((element) => {
    const region = element.parentElement;
    if (!region) throw new Error('Results region missing');
    region.dataset['skeletonAppeared'] = 'false';
    new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          if (
            node instanceof Element &&
            (node.matches('p-skeleton') || node.querySelector('p-skeleton'))
          ) {
            region.dataset['skeletonAppeared'] = 'true';
          }
        }
      }
    }).observe(region, { childList: true, subtree: true });
  });
  const request = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === '/api/product' && url.searchParams.get('name') === 'Monitor';
  });
  try {
    await page.getByRole('searchbox', { name: 'Product name' }).fill('Monitor');
    const sent = await request;
    expect(new URL(sent.url()).searchParams.get('size')).toBe('4');
    expect(new URL(sent.url()).searchParams.get('page')).toBe('0');
    await expect(page.locator('app-admin-products [aria-busy]')).toHaveAttribute(
      'aria-busy',
      'true',
    );
    await expect(list).toHaveCSS('opacity', '0.5');
    await expect(list.getByRole('article')).toHaveCount(4);
    for (const product of products) {
      await expect(list.getByRole('heading', { name: product.name, exact: true })).toBeVisible();
    }
    await expect(page.locator('app-admin-products p-skeleton')).toHaveCount(0);
  } finally {
    releaseResponse();
  }
  await expect(list.getByRole('article')).toHaveCount(1);
  await expect(list.getByRole('heading', { name: 'Monitor', exact: true })).toBeVisible();
  await expect(page.locator('app-admin-products [aria-busy]')).toHaveAttribute(
    'aria-busy',
    'false',
  );
  await expect(page.locator('[data-skeleton-appeared]')).toHaveAttribute(
    'data-skeleton-appeared',
    'false',
  );
});

async function fillProduct(page: Page) {
  await page.getByLabel('Product name', { exact: true }).fill(' New keyboard ');
  await page.getByLabel('Categories', { exact: true }).fill(' New category ');
  await page.getByLabel('Price', { exact: true }).fill('1,234.56');
  await page.getByLabel('Stock', { exact: true }).fill('8');
  await page.getByLabel('Description', { exact: true }).fill(' Mechanical keyboard for work ');
}

test('creates a product with stock, a new category and CSRF and returns with filters and a toast', async ({
  page,
  baseURL,
}) => {
  if (!baseURL) throw new Error('Test URL missing');
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'stock-csrf', url: baseURL }]);
  let created = false;
  let stockCreated = false;
  await page.route('**/api/inventory', async (route) => {
    expect(created).toBe(true);
    expect(route.request().method()).toBe('POST');
    expect(route.request().headers()['x-xsrf-token']).toBe('stock-csrf');
    expect(route.request().postDataJSON()).toEqual({ skuCode: 'new', quantity: 8 });
    stockCreated = true;
    await route.fulfill({ status: 201, json: { skuCode: 'new', quantity: 8, inStock: true } });
  });
  await page.route('**/api/product', async (route) => {
    expect(route.request().method()).toBe('POST');
    expect(route.request().headers()['x-xsrf-token']).toBe('stock-csrf');
    const body: unknown = route.request().postDataJSON();
    expect(body).toEqual({
      name: 'New keyboard',
      description: 'Mechanical keyboard for work',
      category: 'New category',
      price: 1234.56,
      imageUrl: 'https://example.com/new.png',
    });
    created = true;
    await route.fulfill({ status: 201, json: { ...products[0], id: 'new' } });
  });
  await page.goto('/admin/products?name=Keyboard&extra=1');
  await page.getByRole('link', { name: 'ADD', exact: true }).click();
  await expect(page).toHaveTitle('New product | DS Catalog');
  await fillProduct(page);
  await page.getByLabel('Image URL', { exact: true }).fill(' https://example.com/new.png ');
  await page.getByRole('button', { name: 'SAVE', exact: true }).click();
  await expect(page).toHaveURL('/admin/products?name=Keyboard&extra=1');
  await expect(page.getByText('Product created successfully.', { exact: true })).toBeVisible();
  expect(created).toBe(true);
  expect(stockCreated).toBe(true);
});

test('edits with quoted If-Match and preserves imageUrl', async ({ page }) => {
  const loaded = { ...products[0], imageUrl: 'https://example.com/existing.png', version: 3 };
  let updated = false;
  let stockUpdated = false;
  await page.route('**/api/inventory/1', async (route) => {
    expect(updated).toBe(true);
    expect(route.request().method()).toBe('PUT');
    expect(route.request().postDataJSON()).toEqual({ quantity: 8 });
    stockUpdated = true;
    await route.fulfill({ json: { skuCode: '1', quantity: 8, inStock: true } });
  });
  await page.route('**/api/product/1', async (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: loaded });
    expect(route.request().method()).toBe('PUT');
    expect(route.request().headers()['if-match']).toBe('"3"');
    expect(route.request().postDataJSON()).toEqual({
      name: 'New keyboard',
      description: 'Mechanical keyboard for work',
      category: 'New category',
      price: 1234.56,
      imageUrl: 'https://example.com/existing.png',
    });
    updated = true;
    await route.fulfill({ json: { ...loaded, version: 4 } });
  });
  await page.goto('/admin/products?extra=1');
  await page.getByRole('button', { name: 'Edit ' + products[0].name, exact: true }).click();
  await expect(page).toHaveURL('/admin/products/1/edit?extra=1');
  await expect(page).toHaveTitle('Edit product | DS Catalog');
  await expect(page.getByLabel('Price', { exact: true })).toHaveValue('2,779.00');
  await expect(page.getByLabel('Stock', { exact: true })).toHaveValue('12');
  await fillProduct(page);
  await page.getByRole('button', { name: 'SAVE', exact: true }).click();
  await expect(page).toHaveURL('/admin/products?extra=1');
  expect(updated).toBe(true);
  expect(stockUpdated).toBe(true);
});

test('deletes only after confirmation and sends ETag', async ({ page }) => {
  let deleted = false;
  let reads = 0;
  await page.route('**/api/product?**', async (route) => {
    reads++;
    await route.fulfill({
      json: deleted
        ? { ...productPage, content: products.slice(1), totalElements: 3 }
        : productPage,
    });
  });
  await page.route('**/api/product/1', async (route) => {
    expect(route.request().method()).toBe('DELETE');
    expect(route.request().headers()['if-match']).toBe('"0"');
    deleted = true;
    await route.fulfill({ status: 204 });
  });
  await page.goto('/admin/products');
  await page.getByRole('button', { name: 'Delete ' + products[0].name, exact: true }).click();
  const dialog = page.locator('div[role="alertdialog"]');
  await expect(dialog).toContainText(
    'Delete "' + products[0].name + '"? This action cannot be undone.',
  );
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
  expect(deleted).toBe(false);
  await dialog.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(
    page.getByRole('list', { name: 'Products', exact: true }).getByRole('article'),
  ).toHaveCount(3);
  await expect(page.getByText('Product deleted successfully.', { exact: true })).toBeVisible();
  expect(deleted).toBe(true);
  expect(reads).toBe(2);
});

test('canceling deletion does not send DELETE and an HTML name stays literal text', async ({
  page,
}) => {
  let deletes = 0;
  const name = '<img src=x onerror=alert(1)>Product';
  await page.route('**/api/product?**', (route) =>
    route.fulfill({ json: { ...productPage, content: [{ ...products[0], name }] } }),
  );
  page.on('request', (request) => {
    if (request.method() === 'DELETE') deletes++;
  });
  await page.goto('/admin/products');
  await page.getByRole('button', { name: 'Delete ' + name, exact: true }).click();
  const dialog = page.locator('div[role="alertdialog"]');
  await expect(dialog).toContainText(name);
  await expect(dialog.locator('img')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(deletes).toBe(0);
});

test('412 preserves input until an explicit reload and uses the current version on the next attempt', async ({
  page,
}) => {
  let reads = 0;
  let writes = 0;
  await page.route('**/api/product/1', async (route) => {
    if (route.request().method() === 'GET') {
      reads++;
      return route.fulfill({
        json: {
          ...products[0],
          name: reads === 1 ? 'Original' : 'Someone else',
          version: reads === 1 ? 3 : 7,
          imageUrl: reads === 1 ? null : 'https://example.com/new.png',
        },
      });
    }
    writes++;
    if (writes === 1) {
      expect(route.request().headers()['if-match']).toBe('"3"');
      return route.fulfill({ status: 412, json: { detail: 'Outdated version.' } });
    }
    expect(route.request().headers()['if-match']).toBe('"7"');
    expect(route.request().postDataJSON().imageUrl).toBe('https://example.com/new.png');
    await route.fulfill({ json: { ...products[0], version: 8 } });
  });
  await page.goto('/admin/products/1/edit');
  await expect(page.getByLabel('Product name', { exact: true })).toHaveValue('Original');
  await fillProduct(page);
  await page.getByRole('button', { name: 'SAVE', exact: true }).click();
  await expect(page.getByText(/Someone else changed this product/)).toBeVisible();
  await expect(page.getByLabel('Product name', { exact: true })).toHaveValue(' New keyboard ');
  expect(reads).toBe(1);
  expect(writes).toBe(1);
  await page.getByRole('button', { name: 'Reload details', exact: true }).click();
  await expect(page.getByLabel('Product name', { exact: true })).toHaveValue('Someone else');
  await page.getByRole('button', { name: 'SAVE', exact: true }).click();
  await expect(page).toHaveURL('/admin/products');
  expect(writes).toBe(2);
});

test('form follows desktop geometry at 1440px', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto('/admin/products/new');
  const box = page.locator('app-admin-product-form > section');
  const title = page.getByRole('heading', { name: 'PRODUCT DETAILS', level: 1 });
  await expect(title).toBeVisible();
  const bounds = await box.boundingBox();
  const heading = await title.boundingBox();
  const name = await page.getByLabel('Product name', { exact: true }).boundingBox();
  const category = await page.getByLabel('Categories', { exact: true }).boundingBox();
  const price = await page.getByLabel('Price', { exact: true }).boundingBox();
  const description = await page.getByLabel('Description', { exact: true }).boundingBox();
  const cancel = await page.getByRole('button', { name: 'CANCEL', exact: true }).boundingBox();
  const save = await page.getByRole('button', { name: 'SAVE', exact: true }).boundingBox();
  if (!bounds || !heading || !name || !category || !price || !description || !cancel || !save)
    throw new Error('Form missing');
  expect(bounds.x).toBe(345);
  expect(bounds.y).toBe(110);
  expect(bounds.width).toBe(1055);
  expect(heading.y - bounds.y).toBe(75);
  expect(name.x - bounds.x).toBe(47);
  expect(name.y - bounds.y).toBe(151);
  expect(name.width).toBeCloseTo(454, 0);
  expect(name.height).toBe(50);
  expect(category.y - name.y - name.height).toBe(32);
  expect(price.y - category.y - category.height).toBe(32);
  expect(description.x - name.x - name.width).toBeCloseTo(32, 1);
  expect(description.width).toBeCloseTo(475, 0);
  expect(description.height).toBe(447);
  expect(description.y).toBe(name.y);
  expect(save.y - description.y - description.height).toBe(73);
  expect(save.x - cancel.x - cancel.width).toBe(29);
  expect(save.width).toBe(273);
  expect(cancel.height).toBe(60);
  expect(bounds.y + bounds.height - save.y - save.height).toBe(49);
});

test('mobile form does not overflow at 414px and SAVE appears first', async ({ page }) => {
  await page.setViewportSize({ width: 414, height: 1100 });
  await page.goto('/admin/products/new');
  await expect(page.getByRole('heading', { name: 'PRODUCT DETAILS' })).toBeVisible();
  const save = await page.getByRole('button', { name: 'SAVE', exact: true }).boundingBox();
  const cancel = await page.getByRole('button', { name: 'CANCEL', exact: true }).boundingBox();
  const name = await page.getByLabel('Product name', { exact: true }).boundingBox();
  const description = await page.getByLabel('Description', { exact: true }).boundingBox();
  if (!save || !cancel || !name || !description) throw new Error('Form missing');
  expect(name.width).toBe(334);
  expect(description.width).toBe(name.width);
  expect(description.height).toBe(200);
  expect(save.width).toBe(name.width);
  expect(save.y).toBeLessThan(cancel.y);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(414);
});

for (const editing of [false, true]) {
  test(`stock failure preserves the ${editing ? 'edited' : 'created'} product and allows retrying`, async ({
    page,
  }) => {
    let creates = 0;
    let updates = 0;
    let stockWrites = 0;
    const stockMethods: string[] = [];
    await page.route('**/api/product', async (route) => {
      creates++;
      await route.fulfill({ status: 201, json: { ...products[0], version: 1 } });
    });
    await page.route('**/api/product/1', async (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ json: products[0] });
      }
      updates++;
      expect(route.request().headers()['if-match']).toBe(`"${editing ? updates - 1 : updates}"`);
      await route.fulfill({ json: { ...products[0], version: editing ? updates : updates + 1 } });
    });
    await page.route('**/api/inventory**', async (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ json: [{ skuCode: '1', quantity: 12, inStock: true }] });
      }
      stockWrites++;
      stockMethods.push(route.request().method());
      if (stockWrites === 1) {
        return route.fulfill({
          status: 503,
          json: { detail: 'Stock temporarily unavailable.' },
        });
      }
      if (!editing && stockWrites === 2) {
        return route.fulfill({ status: 404, json: { detail: 'Stock not yet registered.' } });
      }
      expect(route.request().postDataJSON()).toEqual(
        route.request().method() === 'POST' ? { skuCode: '1', quantity: 8 } : { quantity: 8 },
      );
      await route.fulfill({
        status: route.request().method() === 'POST' ? 201 : 200,
        json: { skuCode: '1', quantity: 8, inStock: true },
      });
    });
    await page.goto(editing ? '/admin/products/1/edit?extra=1' : '/admin/products/new?extra=1');
    await fillProduct(page);
    await page.getByRole('button', { name: 'SAVE', exact: true }).click();
    await expect(page.getByText(/The product was saved, but stock was not/)).toBeVisible();
    await expect(page.getByText(/Stock temporarily unavailable/)).toBeVisible();
    await expect(page.getByText(/Product (created|updated) successfully/)).toHaveCount(0);
    await expect(page.getByLabel('Stock', { exact: true })).toHaveValue('8');
    await expect(page.getByLabel('Product name', { exact: true })).toHaveValue(' New keyboard ');
    await page.getByRole('button', { name: 'SAVE', exact: true }).click();
    await expect(page).toHaveURL('/admin/products?extra=1');
    expect(creates).toBe(editing ? 0 : 1);
    expect(updates).toBe(editing ? 2 : 1);
    expect(stockMethods).toEqual(editing ? ['PUT', 'PUT'] : ['POST', 'PUT', 'POST']);
  });
}

test('shows card stock with one batch query per page', async ({ page }) => {
  const batches: string[][] = [];
  await mockPaginatedProducts(page);
  await page.route('**/api/inventory?**', async (route) => {
    const codes = new URL(route.request().url()).searchParams.getAll('skuCode');
    batches.push(codes);
    await route.fulfill({
      json: codes.map((skuCode) => ({ skuCode, quantity: Number(skuCode), inStock: true })),
    });
  });
  await page.goto('/admin/products');
  const cards = page.getByRole('list', { name: 'Products', exact: true }).getByRole('article');
  await expect(cards.nth(0).getByText('Stock: 1', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Page 2', exact: true }).click();
  await expect(cards.nth(0).getByText('Stock: 5', { exact: true })).toBeVisible();
  expect(batches).toEqual([
    ['1', '2', '3', '4'],
    ['5', '6', '7', '8'],
  ]);
});

test('card stock stays inside the card at intermediate widths', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 900 });
  await page.goto('/admin/products');
  const cards = page.getByRole('list', { name: 'Products', exact: true }).getByRole('article');
  await expect(cards).toHaveCount(4);
  for (const card of await cards.all()) {
    const stock = card.getByText('Stock: 12', { exact: true });
    await expect(stock).toBeVisible();
    const bounds = await card.boundingBox();
    const stockBounds = await stock.boundingBox();
    if (!bounds || !stockBounds) throw new Error('Card or stock missing');
    expect(stockBounds.y + stockBounds.height).toBeLessThanOrEqual(bounds.y + bounds.height);
    expect(stockBounds.x + stockBounds.width).toBeLessThanOrEqual(bounds.x + bounds.width);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(1024);
});
