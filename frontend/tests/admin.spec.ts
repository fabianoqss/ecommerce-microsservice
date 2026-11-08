import { expect, Page, test } from '@playwright/test';

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
const paginatedProducts = Array.from({ length: 9 }, (_, index) => ({
  ...products[0],
  id: String(index + 1),
  name: index < 6 ? `Teclado ${index + 1}` : `Monitor ${index + 1}`,
  category: index < 6 ? 'Periféricos' : 'Monitores',
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
    route.fulfill({ json: ['Periféricos', 'Monitores'] }),
  );
}

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

test('pagina a lista administrativa pela URL e mostra os cards da página 2', async ({ page }) => {
  await mockPaginatedProducts(page);
  await page.goto('/admin/products?extra=1');
  const list = page.getByRole('list', { name: 'Produtos', exact: true });
  await expect(list.getByRole('heading')).toHaveText([
    'Teclado 1',
    'Teclado 2',
    'Teclado 3',
    'Teclado 4',
  ]);
  await page.getByRole('button', { name: 'Página 2', exact: true }).click();
  await expect(page).toHaveURL('/admin/products?extra=1&page=2');
  await expect(list.getByRole('article')).toHaveCount(4);
  await expect(list.getByRole('heading')).toHaveText([
    'Teclado 5',
    'Teclado 6',
    'Monitor 7',
    'Monitor 8',
  ]);
  await expect(page.getByRole('button', { name: 'Página 2', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
});

for (const filter of ['nome', 'categoria', 'limpar']) {
  test(`filtro ${filter} remove a página da URL e busca a primeira página`, async ({ page }) => {
    await mockPaginatedProducts(page);
    await page.goto(
      filter === 'limpar'
        ? '/admin/products?page=2&name=Teclado&category=Perif%C3%A9ricos&extra=1'
        : '/admin/products?page=2&extra=1',
    );
    await expect(page.getByRole('button', { name: 'Página 2', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );
    if (filter === 'nome') {
      await page.getByRole('searchbox', { name: 'Nome do produto', exact: true }).fill('Monitor');
    } else if (filter === 'categoria') {
      await page
        .getByRole('combobox', { name: 'Categoria', exact: true })
        .selectOption('Monitores');
    } else {
      await page.getByRole('button', { name: 'LIMPAR FILTRO', exact: true }).click();
    }
    await expect(page).toHaveURL(
      filter === 'nome'
        ? '/admin/products?extra=1&name=Monitor'
        : filter === 'categoria'
          ? '/admin/products?extra=1&category=Monitores'
          : '/admin/products?extra=1',
    );
    await expect(
      page.getByRole('list', { name: 'Produtos', exact: true }).getByRole('heading'),
    ).toHaveText(
      filter === 'limpar'
        ? ['Teclado 1', 'Teclado 2', 'Teclado 3', 'Teclado 4']
        : ['Monitor 7', 'Monitor 8', 'Monitor 9'],
    );
  });
}

for (const action of ['CANCELAR', 'SALVAR']) {
  test(`editar da página 2 e ${action} retorna à mesma página`, async ({ page }) => {
    await mockPaginatedProducts(page);
    const product = paginatedProducts[4];
    await page.route('**/api/product/5', async (route) => {
      if (route.request().method() === 'GET') return route.fulfill({ json: product });
      expect(route.request().method()).toBe('PUT');
      await route.fulfill({ json: { ...product, version: 1 } });
    });
    await page.goto('/admin/products?extra=1');
    await page.getByRole('button', { name: 'Página 2', exact: true }).click();
    await expect(page).toHaveURL('/admin/products?extra=1&page=2');
    await page.getByRole('button', { name: 'Editar Teclado 5', exact: true }).click();
    await expect(page).toHaveURL('/admin/products/5/edit?extra=1&page=2');
    await expect(page.getByLabel('Nome do Produto', { exact: true })).toHaveValue('Teclado 5');
    await page.getByRole('button', { name: action, exact: true }).click();
    await expect(page).toHaveURL('/admin/products?extra=1&page=2');
    await expect(
      page.getByRole('list', { name: 'Produtos', exact: true }).getByRole('heading'),
    ).toHaveText(['Teclado 5', 'Teclado 6', 'Monitor 7', 'Monitor 8']);
    await expect(page.getByRole('button', { name: 'Página 2', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });
}

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
    const button = page.getByRole('link', { name: 'ADICIONAR', exact: true });
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
    const button = page.getByRole('link', { name: 'ADICIONAR', exact: true });
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

async function fillProduct(page: Page) {
  await page.getByLabel('Nome do Produto', { exact: true }).fill(' Teclado novo ');
  await page.getByLabel('Categorias', { exact: true }).fill(' Nova categoria ');
  await page.getByLabel('Preço', { exact: true }).fill('1.234,56');
  await page.getByLabel('Descrição', { exact: true }).fill(' Teclado mecânico para trabalho ');
}

test('cria produto com categoria nova, imagem nula e retorna com filtros e toast', async ({
  page,
}) => {
  let created = false;
  await page.route('**/api/product', async (route) => {
    expect(route.request().method()).toBe('POST');
    const body: unknown = route.request().postDataJSON();
    expect(body).toEqual({
      name: 'Teclado novo',
      description: 'Teclado mecânico para trabalho',
      category: 'Nova categoria',
      price: 1234.56,
      imageUrl: null,
    });
    created = true;
    await route.fulfill({ status: 201, json: { ...products[0], id: 'new' } });
  });
  await page.goto('/admin/products?name=Teclado&extra=1');
  await page.getByRole('link', { name: 'ADICIONAR', exact: true }).click();
  await expect(page).toHaveTitle('Novo produto | DS Catalog');
  await fillProduct(page);
  await page.getByRole('button', { name: 'SALVAR', exact: true }).click();
  await expect(page).toHaveURL('/admin/products?name=Teclado&extra=1');
  await expect(page.getByText('Produto criado com sucesso.', { exact: true })).toBeVisible();
  expect(created).toBe(true);
});

test('edita com If-Match entre aspas e preserva imageUrl', async ({ page }) => {
  const loaded = { ...products[0], imageUrl: 'https://example.com/existing.png', version: 3 };
  let updated = false;
  await page.route('**/api/product/1', async (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: loaded });
    expect(route.request().method()).toBe('PUT');
    expect(route.request().headers()['if-match']).toBe('"3"');
    expect(route.request().postDataJSON()).toEqual({
      name: 'Teclado novo',
      description: 'Teclado mecânico para trabalho',
      category: 'Nova categoria',
      price: 1234.56,
      imageUrl: 'https://example.com/existing.png',
    });
    updated = true;
    await route.fulfill({ json: { ...loaded, version: 4 } });
  });
  await page.goto('/admin/products?extra=1');
  await page.getByRole('button', { name: 'Editar ' + products[0].name, exact: true }).click();
  await expect(page).toHaveURL('/admin/products/1/edit?extra=1');
  await expect(page).toHaveTitle('Editar produto | DS Catalog');
  await expect(page.getByLabel('Preço', { exact: true })).toHaveValue('2.779,00');
  await fillProduct(page);
  await page.getByRole('button', { name: 'SALVAR', exact: true }).click();
  await expect(page).toHaveURL('/admin/products?extra=1');
  expect(updated).toBe(true);
});

test('exclui somente após confirmar, enviando ETag', async ({ page }) => {
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
  await page.getByRole('button', { name: 'Excluir ' + products[0].name, exact: true }).click();
  const dialog = page.locator('div[role="alertdialog"]');
  await expect(dialog).toContainText(
    'Excluir "' + products[0].name + '"? Esta ação não pode ser desfeita.',
  );
  await expect(dialog.getByRole('button', { name: 'Cancelar', exact: true })).toBeFocused();
  expect(deleted).toBe(false);
  await dialog.getByRole('button', { name: 'Excluir', exact: true }).click();
  await expect(
    page.getByRole('list', { name: 'Produtos', exact: true }).getByRole('article'),
  ).toHaveCount(3);
  await expect(page.getByText('Produto excluído com sucesso.', { exact: true })).toBeVisible();
  expect(deleted).toBe(true);
  expect(reads).toBe(2);
});

test('cancelar exclusão não envia DELETE e nome com HTML é texto literal', async ({ page }) => {
  let deletes = 0;
  const name = '<img src=x onerror=alert(1)>Produto';
  await page.route('**/api/product?**', (route) =>
    route.fulfill({ json: { ...productPage, content: [{ ...products[0], name }] } }),
  );
  page.on('request', (request) => {
    if (request.method() === 'DELETE') deletes++;
  });
  await page.goto('/admin/products');
  await page.getByRole('button', { name: 'Excluir ' + name, exact: true }).click();
  const dialog = page.locator('div[role="alertdialog"]');
  await expect(dialog).toContainText(name);
  await expect(dialog.locator('img')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Cancelar', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(deletes).toBe(0);
});

test('412 mantém entrada até recarga explícita e usa versão atual na tentativa seguinte', async ({
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
          name: reads === 1 ? 'Original' : 'Outra pessoa',
          version: reads === 1 ? 3 : 7,
          imageUrl: reads === 1 ? null : 'https://example.com/new.png',
        },
      });
    }
    writes++;
    if (writes === 1) {
      expect(route.request().headers()['if-match']).toBe('"3"');
      return route.fulfill({ status: 412, json: { detail: 'Versão desatualizada.' } });
    }
    expect(route.request().headers()['if-match']).toBe('"7"');
    expect(route.request().postDataJSON().imageUrl).toBe('https://example.com/new.png');
    await route.fulfill({ json: { ...products[0], version: 8 } });
  });
  await page.goto('/admin/products/1/edit');
  await expect(page.getByLabel('Nome do Produto', { exact: true })).toHaveValue('Original');
  await fillProduct(page);
  await page.getByRole('button', { name: 'SALVAR', exact: true }).click();
  await expect(page.getByText(/Outra pessoa alterou este produto/)).toBeVisible();
  await expect(page.getByLabel('Nome do Produto', { exact: true })).toHaveValue(' Teclado novo ');
  expect(reads).toBe(1);
  expect(writes).toBe(1);
  await page.getByRole('button', { name: 'Recarregar dados', exact: true }).click();
  await expect(page.getByLabel('Nome do Produto', { exact: true })).toHaveValue('Outra pessoa');
  await page.getByRole('button', { name: 'SALVAR', exact: true }).click();
  await expect(page).toHaveURL('/admin/products');
  expect(writes).toBe(2);
});

test('formulário segue geometria desktop em 1440px', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto('/admin/products/new');
  const box = page.locator('app-admin-product-form > section');
  const title = page.getByRole('heading', { name: 'DADOS DO PRODUTO', level: 1 });
  await expect(title).toBeVisible();
  const bounds = await box.boundingBox();
  const heading = await title.boundingBox();
  const name = await page.getByLabel('Nome do Produto', { exact: true }).boundingBox();
  const category = await page.getByLabel('Categorias', { exact: true }).boundingBox();
  const price = await page.getByLabel('Preço', { exact: true }).boundingBox();
  const description = await page.getByLabel('Descrição', { exact: true }).boundingBox();
  const cancel = await page.getByRole('button', { name: 'CANCELAR', exact: true }).boundingBox();
  const save = await page.getByRole('button', { name: 'SALVAR', exact: true }).boundingBox();
  if (!bounds || !heading || !name || !category || !price || !description || !cancel || !save)
    throw new Error('Formulário ausente');
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

test('formulário móvel não transborda em 414px e SALVAR aparece primeiro', async ({ page }) => {
  await page.setViewportSize({ width: 414, height: 1100 });
  await page.goto('/admin/products/new');
  await expect(page.getByRole('heading', { name: 'DADOS DO PRODUTO' })).toBeVisible();
  const save = await page.getByRole('button', { name: 'SALVAR', exact: true }).boundingBox();
  const cancel = await page.getByRole('button', { name: 'CANCELAR', exact: true }).boundingBox();
  const name = await page.getByLabel('Nome do Produto', { exact: true }).boundingBox();
  const description = await page.getByLabel('Descrição', { exact: true }).boundingBox();
  if (!save || !cancel || !name || !description) throw new Error('Formulário ausente');
  expect(name.width).toBe(334);
  expect(description.width).toBe(name.width);
  expect(description.height).toBe(200);
  expect(save.width).toBe(name.width);
  expect(save.y).toBeLessThan(cancel.y);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(414);
});
