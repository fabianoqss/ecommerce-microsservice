import { expect, test } from '@playwright/test';

const products = Array.from({ length: 4 }, (_, index) => ({
  id: String(index + 1),
  name: 'Computador com nome extenso para verificar truncamento ' + (index + 1),
  description: 'Computador para trabalho',
  price: 2779,
  category: 'Computadores e acessórios com categoria extensa',
  imageUrl: null,
  version: 0,
}));
const productPage = { content: products, page: 0, size: 4, totalElements: 4, totalPages: 1 };

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
      json: productPage,
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
  expect(bounds?.height).toBeGreaterThanOrEqual(830);
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

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 1024, height: 900 },
]) {
  test(`toolbar alinha botão e busca no desktop em ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/admin/products');
    const button = page.getByRole('button', { name: 'ADICIONAR', exact: true });
    const search = page.locator('app-admin-products app-product-search');
    await expect(button).toBeVisible();
    await expect(search).toBeVisible();
    const buttonBounds = await button.boundingBox();
    const searchBounds = await search.boundingBox();
    if (!buttonBounds || !searchBounds) throw new Error('Toolbar ausente');
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
  test(`toolbar empilha botão e busca no mobile em ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/admin/products');
    const button = page.getByRole('button', { name: 'ADICIONAR', exact: true });
    const search = page.locator('app-admin-products app-product-search');
    await expect(button).toBeVisible();
    await expect(search).toBeVisible();
    const buttonBounds = await button.boundingBox();
    const searchBounds = await search.boundingBox();
    if (!buttonBounds || !searchBounds) throw new Error('Toolbar ausente');
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

test('digitar nome na busca administrativa atualiza a URL', async ({ page }) => {
  await page.goto('/admin/products');
  await page.getByRole('searchbox', { name: 'Nome do produto' }).fill('Teclado');
  await expect(page).toHaveURL('/admin/products?name=Teclado');
  await expect(page.getByRole('searchbox', { name: 'Nome do produto' })).toHaveValue('Teclado');
});

test('cards seguem a geometria do design em 1440px', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/admin/products');
  const list = page.getByRole('list', { name: 'Produtos', exact: true });
  const cards = list.getByRole('article');
  await expect(cards).toHaveCount(4);
  const first = await cards.nth(0).boundingBox();
  const second = await cards.nth(1).boundingBox();
  if (!first || !second) throw new Error('Cards ausentes');
  expect(first).toEqual({ x: 345, y: 199, width: 1055, height: 160 });
  expect(second.y - first.y - first.height).toBe(21);
  for (const [label, offset] of [
    ['Editar', 31],
    ['Excluir', 91],
  ] as const) {
    const button = cards.nth(0).getByRole('button', { name: label + ' ' + products[0].name });
    const bounds = await button.boundingBox();
    if (!bounds) throw new Error('Botão ausente');
    expect(bounds.width).toBe(213);
    expect(bounds.height).toBe(38);
    expect(bounds.x).toBe(first.x + first.width - 17 - 213);
    expect(bounds.y).toBe(first.y + offset);
  }
});

test('cards não transbordam e têm botões lado a lado em 414px', async ({ page }) => {
  await page.setViewportSize({ width: 414, height: 896 });
  await page.goto('/admin/products');
  const cards = page.getByRole('list', { name: 'Produtos', exact: true }).getByRole('article');
  await expect(cards).toHaveCount(4);
  for (const card of await cards.all()) {
    const bounds = await card.boundingBox();
    const buttons = card.getByRole('button');
    const edit = await buttons.nth(0).boundingBox();
    const remove = await buttons.nth(1).boundingBox();
    if (!bounds || !edit || !remove) throw new Error('Card ou botões ausentes');
    expect(bounds.x).toBe(20);
    expect(bounds.width).toBe(374);
    expect(edit.y).toBe(remove.y);
    expect(edit.width).toBe(remove.width);
    expect(remove.x).toBeGreaterThan(edit.x);
    expect(remove.x + remove.width).toBeLessThanOrEqual(bounds.x + bounds.width);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(414);
});

test('mantém os quatro cards sem skeleton durante resposta atrasada do filtro', async ({
  page,
}) => {
  await page.goto('/admin/products');
  const list = page.getByRole('list', { name: 'Produtos', exact: true });
  await expect(list.getByRole('article')).toHaveCount(4);
  let releaseResponse: () => void = () => {
    throw new Error('Resposta pendente não inicializada');
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
    if (!region) throw new Error('Região de resultados ausente');
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
    await page.getByRole('searchbox', { name: 'Nome do produto' }).fill('Monitor');
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
