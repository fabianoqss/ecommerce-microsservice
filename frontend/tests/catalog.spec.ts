import { expect, test } from '@playwright/test';

const products = Array.from({ length: 8 }, (_, index) => ({
  id: String(index + 1),
  name: index === 0 ? 'Teclado Mecânico TKL' : `Produto ${index + 1}`,
  description: 'Produto do catálogo',
  price: index === 0 ? 1999 : 89.9,
  category: 'Periféricos',
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
    await route.fulfill({ json: ['Acessórios', 'Armazenamento', 'Periféricos'] });
  });
  await page.route('**/bff/me', async (route) => {
    await route.fulfill({ status: 401, json: { status: 401, title: 'Não autenticado' } });
  });
});

test('lista oito cards com preço e link ativo', async ({ page }) => {
  await page.goto('/catalog');
  await expect(page).toHaveTitle('Catálogo | DS Catalog');
  await expect(page.getByRole('heading', { level: 1, name: 'Catálogo de produtos' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Produtos' }).getByRole('article')).toHaveCount(8);
  await expect(page.getByRole('heading', { name: 'Teclado Mecânico TKL' })).toBeVisible();
  await expect(
    page.getByRole('list', { name: 'Produtos' }).getByRole('link').first(),
  ).toHaveAttribute('href', '/catalog/1');
  await expect(page.getByTestId('price-integer').first()).toHaveText('1.999');
  await expect(page.getByTestId('price-decimal').first()).toHaveText(',00');
  await expect(page.getByRole('link', { name: 'CATÁLOGO' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  const navigation = page.getByRole('navigation', { name: 'Paginação' });
  await expect(
    navigation.getByRole('button', { name: 'Página anterior', exact: true }),
  ).toBeDisabled();
  await expect(navigation.getByRole('button', { name: /^Página \d+$/ })).toHaveCount(10);
});

test('troca a página pela URL, rola para a lista e permite voltar', async ({ page }) => {
  await page.goto('/catalog');
  await page.getByRole('button', { name: 'Página 2', exact: true }).click();
  await expect(page).toHaveURL(/\/catalog\?page=2$/);
  await expect(page.getByRole('button', { name: 'Página 2', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect
    .poll(() =>
      page
        .getByRole('list', { name: 'Produtos' })
        .evaluate((list) => list.getBoundingClientRect().top),
    )
    .toBeLessThan(100);
  await page.goBack();
  await expect(page).toHaveURL(/\/catalog$/);
  await expect(page.getByRole('button', { name: 'Página 1', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
});

test('envia nome aparado depois do debounce e volta à primeira página', async ({ page }) => {
  await page.goto('/catalog?page=3');
  const request = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === '/api/product' && url.searchParams.get('name') === 'Teclado';
  });
  await page.getByRole('searchbox', { name: 'Nome do produto' }).fill(' Teclado ');
  const url = new URL((await request).url());
  expect(url.searchParams.get('page')).toBe('0');
  expect(url.searchParams.get('size')).toBe('8');
  await expect(page).toHaveURL(/\/catalog\?name=Teclado$/);
});

test('troca categoria, preserva nome e restaura os filtros ao voltar', async ({ page }) => {
  await page.goto('/catalog?page=3&name=Teclado');
  await expect(page.getByRole('combobox', { name: 'Categoria' })).toBeEnabled();
  const request = page.waitForRequest(
    (request) => new URL(request.url()).searchParams.get('category') === 'Periféricos',
  );
  await page.getByRole('combobox', { name: 'Categoria' }).selectOption('Periféricos');
  const url = new URL((await request).url());
  expect(url.searchParams.get('page')).toBe('0');
  expect(url.searchParams.get('name')).toBe('Teclado');
  await expect(page).toHaveURL(/category=Perif%C3%A9ricos/);
  await page.goBack();
  await expect(page.getByRole('combobox', { name: 'Categoria' })).toHaveValue('');
  await expect(page.getByRole('searchbox', { name: 'Nome do produto' })).toHaveValue('Teclado');
  await expect(page.getByRole('button', { name: 'Página 3', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
});

test('mostra vazio e remove filtros e página ao limpar', async ({ page }) => {
  await page.route('**/api/product?**', async (route) => {
    const filtered = new URL(route.request().url()).searchParams.has('name');
    await route.fulfill({
      json: filtered
        ? { ...productPage, content: [], totalElements: 0, totalPages: 0 }
        : productPage,
    });
  });
  await page.goto('/catalog?page=3&name=Inexistente&category=Perif%C3%A9ricos');
  await expect(page.getByText('Nenhum produto encontrado.', { exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Paginação' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Limpar filtros', exact: true }).click();
  await expect(page).toHaveURL(/\/catalog$/);
  await expect(page.getByRole('article')).toHaveCount(8);
  await expect(page.getByRole('searchbox', { name: 'Nome do produto' })).toHaveValue('');
});

test('mostra ProblemDetail e recarrega ao tentar novamente', async ({ page }) => {
  let unavailable = true;
  await page.route('**/api/product?**', async (route) => {
    await route.fulfill(
      unavailable
        ? {
            status: 503,
            json: {
              status: 503,
              title: 'Indisponível',
              detail: 'Serviço de produtos indisponível.',
            },
          }
        : { json: productPage },
    );
  });
  await page.goto('/catalog');
  await expect(page.getByRole('alert')).toBeVisible();
  unavailable = false;
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(page.getByRole('article')).toHaveCount(8);
});

test('continua buscando produtos quando categorias estão indisponíveis', async ({ page }) => {
  await page.route('**/api/product/categories', async (route) => {
    await route.fulfill({ status: 404, json: { status: 404 } });
  });
  await page.goto('/catalog');
  await expect(page.getByRole('article')).toHaveCount(8);
  await expect(page.getByRole('combobox', { name: 'Categoria' })).toBeDisabled();
  await expect(page.getByRole('searchbox', { name: 'Nome do produto' })).toBeEnabled();
});

test('adapta busca, grade e paginação para mobile sem transbordar', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/catalog?page=18');
  await expect(page.getByRole('article')).toHaveCount(8);
  const navigation = page.getByRole('navigation', { name: 'Paginação' });
  expect(
    await navigation.getByRole('button', { name: /^Página \d+$/ }).count(),
  ).toBeLessThanOrEqual(6);
  await expect(page.getByRole('button', { name: 'Página 18', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
  const cards = await page
    .getByRole('article')
    .evaluateAll((cards) => cards.map((card) => card.getBoundingClientRect().x));
  expect(new Set(cards).size).toBe(1);
});
