import { expect, test } from '@playwright/test';

const product = {
  id: '670000000000000000000001',
  name: 'Teclado Mecânico TKL',
  description: 'Teclado mecânico tenkeyless.\nCom teclas iluminadas.',
  price: 349.9,
  category: 'Periféricos',
  imageUrl: null,
  version: 0,
};
const detailUrl = '/catalog/' + product.id;

test.beforeEach(async ({ page }) => {
  await page.route('**/api/product**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/product/categories') {
      await route.fulfill({ json: ['Periféricos'] });
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
        json: { status: 404, title: 'Não encontrado', detail: 'O produto não existe.' },
      });
    }
  });
  await page.route('**/bff/me', async (route) => {
    await route.fulfill({ status: 401, json: { status: 401, title: 'Não autenticado' } });
  });
});

test('abre pelo card e volta preservando página, nome e categoria', async ({ page }) => {
  const query = '?page=2&name=Teclado&category=Perif%C3%A9ricos';
  await page.goto('/catalog' + query);
  const card = page.getByRole('list', { name: 'Produtos' }).getByRole('link');
  await expect(card).toHaveAttribute('href', detailUrl + query);
  await card.focus();
  await expect(card).toBeFocused();
  await card.press('Enter');
  await expect(page).toHaveURL(detailUrl + query);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(product.name);
  await expect(page).toHaveTitle(product.name + ' | DS Catalog');
  await expect(page.getByRole('link', { name: 'CATÁLOGO', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(page.getByTestId('price-integer')).toHaveText('349');
  await expect(page.getByTestId('price-decimal')).toHaveText(',90');
  await expect(page.getByRole('heading', { name: 'Descrição do produto' })).toBeVisible();
  await expect(page.getByText(product.description)).toBeVisible();
  await page.getByRole('link', { name: 'VOLTAR', exact: true }).click();
  await expect(page).toHaveURL('/catalog' + query);
  await expect(page.getByRole('searchbox', { name: 'Nome do produto' })).toHaveValue('Teclado');
  await expect(page.getByRole('combobox', { name: 'Categoria' })).toHaveValue('Periféricos');
  await expect(page.getByRole('button', { name: 'Página 2', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(page).toHaveTitle('Catálogo | DS Catalog');
});

test('abre diretamente e volta ao catálogo sem depender do histórico', async ({ page }) => {
  await page.goto(detailUrl);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(product.name);
  await expect(page).toHaveTitle(product.name + ' | DS Catalog');
  await page.getByRole('link', { name: 'VOLTAR', exact: true }).click();
  await expect(page).toHaveURL('/catalog');
  await expect(page.getByRole('searchbox', { name: 'Nome do produto' })).toHaveValue('');
});

test('mostra skeleton e título provisório enquanto carrega', async ({ page }) => {
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
    await expect(page).toHaveTitle('Produto | DS Catalog');
    await expect(page.getByRole('status')).toHaveText('Carregando produto.');
    await expect(page.locator('p-skeleton')).toHaveCount(4);
  } finally {
    release();
  }
  await expect(page).toHaveTitle(product.name + ' | DS Catalog');
  await expect(page.locator('p-skeleton')).toHaveCount(0);
});

test('mostra 404 e permite retornar ao catálogo', async ({ page }) => {
  await page.goto('/catalog/inexistente');
  await expect(page.getByRole('heading', { name: 'Produto não encontrado.' })).toBeVisible();
  await expect(page).toHaveTitle('Produto | DS Catalog');
  await page.getByRole('link', { name: 'Voltar ao catálogo', exact: true }).click();
  await expect(page).toHaveURL('/catalog');
});

test('mostra ProblemDetail e permite tentar novamente', async ({ page }) => {
  let unavailable = true;
  await page.route('**/api/product/' + product.id, async (route) => {
    await route.fulfill(
      unavailable
        ? { status: 503, json: { status: 503, detail: 'Serviço indisponível.' } }
        : { json: product },
    );
  });
  await page.goto(detailUrl);
  await expect(page.getByRole('alert')).toContainText('Serviço indisponível.');
  unavailable = false;
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(product.name);
});

test('usa duas colunas no desktop e uma no mobile sem transbordar', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(detailUrl);
  const name = page.getByRole('heading', { level: 1 });
  const description = page.getByRole('heading', { name: 'Descrição do produto' });
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
