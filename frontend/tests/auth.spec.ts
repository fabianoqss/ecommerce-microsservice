import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/bff/me', async (route) => {
    await route.fulfill({ status: 401, json: { status: 401, title: 'Não autenticado' } });
  });
});

test('mostra ENTRAR no header de visitantes sem ADMIN nem SAIR', async ({ page }) => {
  await page.goto('/');
  const header = page.getByRole('banner');

  await expect(header.getByRole('button', { name: 'ENTRAR', exact: true })).toBeVisible();
  await expect(header.getByRole('button', { name: 'SAIR', exact: true })).toHaveCount(0);
  await expect(header.getByRole('link', { name: 'ADMIN', exact: true })).toHaveCount(0);
});

test('mostra o primeiro nome, SAIR e link ADMIN para administradores', async ({ page }) => {
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
  await page.goto('/');
  const header = page.getByRole('banner');

  await expect(header.getByText('Ana', { exact: true })).toBeVisible();
  await expect(header.getByRole('button', { name: 'SAIR', exact: true })).toBeVisible();
  await expect(header.getByRole('button', { name: 'ENTRAR', exact: true })).toHaveCount(0);
  await expect(header.getByRole('link', { name: 'ADMIN', exact: true })).toHaveAttribute(
    'href',
    '/admin',
  );
});

test('ENTRAR navega para o endpoint de autorização do gateway', async ({ page }) => {
  await page.route('**/oauth2/authorization/keycloak', async (route) => {
    await route.fulfill({
      contentType: 'text/html',
      body: '<!doctype html><html lang="pt-BR"><title>Login do gateway</title></html>',
    });
  });
  await page.goto('/');
  const origin = new URL(page.url()).origin;
  const [request] = await Promise.all([
    page.waitForRequest('**/oauth2/authorization/keycloak'),
    page.getByRole('button', { name: 'ENTRAR', exact: true }).click(),
  ]);

  expect(request.url()).toBe(origin + '/oauth2/authorization/keycloak');
  expect(request.method()).toBe('GET');
  expect(request.isNavigationRequest()).toBe(true);
  await expect(page).toHaveURL(origin + '/oauth2/authorization/keycloak');
});
