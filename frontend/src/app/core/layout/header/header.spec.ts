import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AUTH_LOCATION } from '../../auth/auth-location';
import { AuthService } from '../../auth/auth.service';
import { Header } from './header';
import { Cart } from '../../cart/cart';
import { CartStore } from '../../cart/cart-store';
import { LocalStorageCartStore } from '../../cart/local-storage-cart-store';

@Component({ template: '' })
class RouteStub {}

describe('Header', () => {
  let http: HttpTestingController;
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: CartStore, useClass: LocalStorageCartStore },
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AUTH_LOCATION, useValue: { assign } },
        provideRouter([
          { path: '', component: RouteStub },
          { path: 'catalog', component: RouteStub },
          { path: 'catalog/:id', component: RouteStub },
          { path: 'admin', component: RouteStub },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  async function render(roles?: string[], name = 'Ana Silva') {
    if (roles) {
      const pending = TestBed.inject(AuthService).load();
      http.expectOne('/bff/me').flush({
        sub: 'user-1',
        name,
        email: 'ana@example.com',
        roles,
      });
      await pending;
    }
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('shows SIGN IN without ADMIN or SIGN OUT for visitors and starts login on click', async () => {
    const { element } = await render();
    const buttons = Array.from(element.querySelectorAll('button'));
    const login = buttons.find((button) => button.textContent?.trim() === 'SIGN IN');

    expect(element.querySelector('[aria-label*="Cart"]')).toBeNull();
    expect(login).toBeDefined();
    expect(login?.type).toBe('button');
    expect(buttons.some((button) => button.textContent?.includes('SIGN OUT'))).toBe(false);
    expect(element.querySelector('a[href="/admin"]')).toBeNull();
    expect(element.textContent).not.toContain('ADMIN');
    login?.click();
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it('shows the first name and SIGN OUT without ADMIN for CUSTOMER and submits logout', async () => {
    const { element } = await render(['CUSTOMER'], '  Ana   Maria Silva  ');
    const logout = Array.from(element.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'SIGN OUT',
    );

    expect(element.textContent).toContain('Ana');
    expect(element.textContent).not.toContain('Maria');
    expect(element.textContent).not.toContain('Silva');
    expect(element.textContent).not.toContain('SIGN IN');
    expect(element.textContent).not.toContain('ADMIN');
    expect(logout).toBeDefined();
    expect(logout?.type).toBe('button');
    logout?.click();
    http.expectOne('/logout').flush(null, {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: '/' },
    });
    expect(assign).toHaveBeenCalledExactlyOnceWith('/');
  });

  it('shows ADMIN as a link to /admin only for administrators', async () => {
    const { fixture, element } = await render(['CUSTOMER', 'ADMIN']);
    const admin = element.querySelector('a[href="/admin"]');

    expect(admin?.textContent?.trim()).toBe('ADMIN');
    expect(element.textContent).toContain('Ana');
    expect(element.textContent).toContain('SIGN OUT');
    await TestBed.inject(Router).navigateByUrl('/admin');
    await fixture.whenStable();
    expect(admin?.getAttribute('aria-current')).toBe('page');
    expect(admin?.classList.contains('font-bold')).toBe(true);

    TestBed.inject(AuthService).clear();
    await fixture.whenStable();
    expect(element.querySelector('a[href="/admin"]')).toBeNull();
    expect(element.textContent).toContain('SIGN IN');
    expect(element.textContent).not.toContain('Ana');
  });

  it('marks only the current route link as active', async () => {
    const { fixture, element } = await render();
    await TestBed.inject(Router).navigateByUrl('/catalog');
    await fixture.whenStable();
    const catalog = element.querySelector('a[href="/catalog"]');
    expect(catalog?.getAttribute('aria-current')).toBe('page');
    expect(catalog?.classList.contains('font-bold')).toBe(true);
    expect(element.querySelector('a[href="/"]')?.hasAttribute('aria-current')).toBe(false);
    await TestBed.inject(Router).navigateByUrl('/catalog/1?page=2&name=Keyboard');
    await fixture.whenStable();
    expect(catalog?.getAttribute('aria-current')).toBe('page');
    expect(catalog?.classList.contains('font-bold')).toBe(true);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    expect(element.querySelector('a[href="/"]')?.getAttribute('aria-current')).toBe('page');
    expect(catalog?.hasAttribute('aria-current')).toBe(false);
  });

  it('initially hides the panel and gives the button an accessible name', async () => {
    const { element } = await render();
    const toggle = element.querySelector<HTMLButtonElement>('button[aria-controls]');

    expect(element.querySelector('nav[aria-label="Mobile navigation"]')).toBeNull();
    expect(toggle?.getAttribute('aria-label')).toBe('Open menu');
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
    expect(toggle?.type).toBe('button');
    expect(toggle?.querySelector('img')?.getAttribute('src')).toBe('/images/icons/menu.svg');
    expect(toggle?.querySelector('img')?.getAttribute('alt')).toBe('');
  });

  it('opens the panel with the button and updates its accessible toggle state', async () => {
    const { fixture, element } = await render();
    const toggle = element.querySelector<HTMLButtonElement>('button[aria-controls]');

    toggle?.click();
    await fixture.whenStable();
    const panel = element.querySelector('nav[aria-label="Mobile navigation"]');
    expect(panel).not.toBeNull();
    expect(panel?.id).toBe(toggle?.getAttribute('aria-controls'));
    expect(
      Array.from(panel?.querySelectorAll('a') ?? []).map((link) => link.textContent?.trim()),
    ).toEqual(['HOME', 'CATALOG']);
    expect(panel?.textContent).toContain('SIGN IN');
    expect(toggle?.getAttribute('aria-expanded')).toBe('true');
    expect(toggle?.getAttribute('aria-label')).toBe('Close menu');

    toggle?.click();
    await fixture.whenStable();
    expect(element.querySelector('nav[aria-label="Mobile navigation"]')).toBeNull();
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
  });

  it.each([
    { roles: undefined, admin: false },
    { roles: ['CUSTOMER'], admin: false },
    { roles: ['CUSTOMER', 'ADMIN'], admin: true },
  ])('shows ADMIN in the panel only for administrators: $roles', async ({ roles, admin }) => {
    const { fixture, element } = await render(roles);
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const panel = element.querySelector('nav[aria-label="Mobile navigation"]');

    expect(panel).not.toBeNull();
    expect(panel?.textContent?.includes('ADMIN')).toBe(admin);
    expect(panel?.querySelector('a[href="/admin"]') !== null).toBe(admin);
    if (roles) {
      expect(panel?.textContent).toContain('Ana');
      expect(panel?.textContent).toContain('SIGN OUT');
    }
  });

  it('closes the panel when selecting a link and navigates to the catalog', async () => {
    const { fixture, element } = await render();
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const catalog = element.querySelector<HTMLAnchorElement>(
      'nav[aria-label="Mobile navigation"] a[href="/catalog"]',
    );
    expect(catalog).not.toBeNull();

    catalog?.click();
    await fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/catalog');
    expect(element.querySelector('nav[aria-label="Mobile navigation"]')).toBeNull();
  });

  it('marks the current route in the panel and distinguishes both navigation regions', async () => {
    const { fixture, element } = await render(['ADMIN']);
    await TestBed.inject(Router).navigateByUrl('/admin');
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const navs = Array.from(element.querySelectorAll('nav'));
    const panel = element.querySelector('nav[aria-label="Mobile navigation"]');

    expect(navs).toHaveLength(2);
    expect(navs[0]?.getAttribute('aria-label')).not.toBe(navs[1]?.getAttribute('aria-label'));
    expect(panel?.querySelector('a[href="/admin"]')?.getAttribute('aria-current')).toBe('page');
    expect(panel?.querySelector('a[href="/"]')?.hasAttribute('aria-current')).toBe(false);
    expect(panel?.querySelector('a[href="/catalog"]')?.hasAttribute('aria-current')).toBe(false);
  });

  it('closes the panel with Escape and returns focus to the menu button', async () => {
    const { fixture, element } = await render();
    const toggle = element.querySelector<HTMLButtonElement>('button[aria-controls]');
    toggle?.click();
    await fixture.whenStable();
    const home = element.querySelector<HTMLAnchorElement>(
      'nav[aria-label="Mobile navigation"] a[href="/"]',
    );
    expect(home).not.toBeNull();
    home?.focus();
    expect(document.activeElement).toBe(home);

    home?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(element.querySelector('nav[aria-label="Mobile navigation"]')).toBeNull();
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(toggle);
  });

  it('closes the panel when starting login', async () => {
    const { fixture, element } = await render();
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const login = element.querySelector<HTMLButtonElement>(
      'nav[aria-label="Mobile navigation"] button',
    );
    expect(login?.textContent?.trim()).toBe('SIGN IN');

    login?.click();
    await fixture.whenStable();
    expect(element.querySelector('nav[aria-label="Mobile navigation"]')).toBeNull();
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it('closes the panel when starting logout', async () => {
    const { fixture, element } = await render(['CUSTOMER']);
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const logout = element.querySelector<HTMLButtonElement>(
      'nav[aria-label="Mobile navigation"] button',
    );
    expect(logout?.textContent?.trim()).toBe('SIGN OUT');

    logout?.click();
    await fixture.whenStable();
    expect(element.querySelector('nav[aria-label="Mobile navigation"]')).toBeNull();
    http.expectOne('/logout').flush(null, {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: '/' },
    });
    expect(assign).toHaveBeenCalledExactlyOnceWith('/');
  });
  it('shows an accessible link and total quantity badge on desktop and mobile', async () => {
    const { fixture, element } = await render(['CUSTOMER']);
    TestBed.inject(Cart).add('p1', 2);
    TestBed.inject(Cart).add('p2');
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const links = element.querySelectorAll('a[aria-label="Cart, 3 items"]');
    expect(links).toHaveLength(2);
    for (const link of links) {
      expect(link.getAttribute('href')).toBe('/cart');
      expect(link.textContent?.trim()).toBe('3');
      expect(link.querySelector('.pi-shopping-cart')).not.toBeNull();
    }
    TestBed.inject(Cart).clear();
    await fixture.whenStable();
    const empty = element.querySelector('a[aria-label="Cart, 0 items"]');
    expect(empty).not.toBeNull();
    expect(empty?.textContent?.trim()).toBe('');
  });
});
