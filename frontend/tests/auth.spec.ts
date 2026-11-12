import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/bff/me', async (route) => {
    await route.fulfill({ status: 401, json: { status: 401, title: 'Unauthenticated' } });
  });
});

test('shows SIGN IN in the visitor header without ADMIN or SIGN OUT', async ({ page }) => {
  await page.goto('/');
  const header = page.getByRole('banner');

  await expect(header.getByRole('button', { name: 'SIGN IN', exact: true })).toBeVisible();
  await expect(header.getByRole('button', { name: 'SIGN OUT', exact: true })).toHaveCount(0);
  await expect(header.getByRole('link', { name: 'ADMIN', exact: true })).toHaveCount(0);
});

test('shows the first name, SIGN OUT and ADMIN link for administrators', async ({ page }) => {
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
  await expect(header.getByRole('button', { name: 'SIGN OUT', exact: true })).toBeVisible();
  await expect(header.getByRole('button', { name: 'SIGN IN', exact: true })).toHaveCount(0);
  await expect(header.getByRole('link', { name: 'ADMIN', exact: true })).toHaveAttribute(
    'href',
    '/admin',
  );
});

test('SIGN IN navigates to the gateway authorization endpoint', async ({ page }) => {
  await page.route('**/oauth2/authorization/keycloak', async (route) => {
    await route.fulfill({
      contentType: 'text/html',
      body: '<!doctype html><html lang="en-US"><title>Gateway login</title></html>',
    });
  });
  await page.goto('/');
  const origin = new URL(page.url()).origin;
  const [request] = await Promise.all([
    page.waitForRequest('**/oauth2/authorization/keycloak'),
    page.getByRole('button', { name: 'SIGN IN', exact: true }).click(),
  ]);

  expect(request.url()).toBe(origin + '/oauth2/authorization/keycloak');
  expect(request.method()).toBe('GET');
  expect(request.isNavigationRequest()).toBe(true);
  await expect(page).toHaveURL(origin + '/oauth2/authorization/keycloak');
});
