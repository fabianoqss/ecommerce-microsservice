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
        sub: 'usuario-1',
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

  it('mostra ENTRAR sem ADMIN nem SAIR para visitantes e inicia o login ao clicar', async () => {
    const { element } = await render();
    const buttons = Array.from(element.querySelectorAll('button'));
    const login = buttons.find((button) => button.textContent?.trim() === 'ENTRAR');

    expect(element.querySelector('[aria-label*="Carrinho"]')).toBeNull();
    expect(login).toBeDefined();
    expect(login?.type).toBe('button');
    expect(buttons.some((button) => button.textContent?.includes('SAIR'))).toBe(false);
    expect(element.querySelector('a[href="/admin"]')).toBeNull();
    expect(element.textContent).not.toContain('ADMIN');
    login?.click();
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it('mostra o primeiro nome e SAIR sem ADMIN para CUSTOMER e envia o logout', async () => {
    const { element } = await render(['CUSTOMER'], '  Ana   Maria Silva  ');
    const logout = Array.from(element.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'SAIR',
    );

    expect(element.textContent).toContain('Ana');
    expect(element.textContent).not.toContain('Maria');
    expect(element.textContent).not.toContain('Silva');
    expect(element.textContent).not.toContain('ENTRAR');
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

  it('mostra ADMIN como link para /admin somente para administradores', async () => {
    const { fixture, element } = await render(['CUSTOMER', 'ADMIN']);
    const admin = element.querySelector('a[href="/admin"]');

    expect(admin?.textContent?.trim()).toBe('ADMIN');
    expect(element.textContent).toContain('Ana');
    expect(element.textContent).toContain('SAIR');
    await TestBed.inject(Router).navigateByUrl('/admin');
    await fixture.whenStable();
    expect(admin?.getAttribute('aria-current')).toBe('page');
    expect(admin?.classList.contains('font-bold')).toBe(true);

    TestBed.inject(AuthService).clear();
    await fixture.whenStable();
    expect(element.querySelector('a[href="/admin"]')).toBeNull();
    expect(element.textContent).toContain('ENTRAR');
    expect(element.textContent).not.toContain('Ana');
  });

  it('marca somente o link da rota atual como ativo', async () => {
    const { fixture, element } = await render();
    await TestBed.inject(Router).navigateByUrl('/catalog');
    await fixture.whenStable();
    const catalog = element.querySelector('a[href="/catalog"]');
    expect(catalog?.getAttribute('aria-current')).toBe('page');
    expect(catalog?.classList.contains('font-bold')).toBe(true);
    expect(element.querySelector('a[href="/"]')?.hasAttribute('aria-current')).toBe(false);
    await TestBed.inject(Router).navigateByUrl('/catalog/1?page=2&name=Teclado');
    await fixture.whenStable();
    expect(catalog?.getAttribute('aria-current')).toBe('page');
    expect(catalog?.classList.contains('font-bold')).toBe(true);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    expect(element.querySelector('a[href="/"]')?.getAttribute('aria-current')).toBe('page');
    expect(catalog?.hasAttribute('aria-current')).toBe(false);
  });

  it('mantém o painel ausente inicialmente e o botão com nome acessível', async () => {
    const { element } = await render();
    const toggle = element.querySelector<HTMLButtonElement>('button[aria-controls]');

    expect(element.querySelector('nav[aria-label="Navegação móvel"]')).toBeNull();
    expect(toggle?.getAttribute('aria-label')).toBe('Abrir menu');
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
    expect(toggle?.type).toBe('button');
    expect(toggle?.querySelector('img')?.getAttribute('src')).toBe('/images/icons/menu.svg');
    expect(toggle?.querySelector('img')?.getAttribute('alt')).toBe('');
  });

  it('abre o painel pelo botão e atualiza seu estado acessível ao alternar', async () => {
    const { fixture, element } = await render();
    const toggle = element.querySelector<HTMLButtonElement>('button[aria-controls]');

    toggle?.click();
    await fixture.whenStable();
    const panel = element.querySelector('nav[aria-label="Navegação móvel"]');
    expect(panel).not.toBeNull();
    expect(panel?.id).toBe(toggle?.getAttribute('aria-controls'));
    expect(
      Array.from(panel?.querySelectorAll('a') ?? []).map((link) => link.textContent?.trim()),
    ).toEqual(['HOME', 'CATÁLOGO']);
    expect(panel?.textContent).toContain('ENTRAR');
    expect(toggle?.getAttribute('aria-expanded')).toBe('true');
    expect(toggle?.getAttribute('aria-label')).toBe('Fechar menu');

    toggle?.click();
    await fixture.whenStable();
    expect(element.querySelector('nav[aria-label="Navegação móvel"]')).toBeNull();
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
  });

  it.each([
    { roles: undefined, admin: false },
    { roles: ['CUSTOMER'], admin: false },
    { roles: ['CUSTOMER', 'ADMIN'], admin: true },
  ])('mostra ADMIN no painel somente para administradores: $roles', async ({ roles, admin }) => {
    const { fixture, element } = await render(roles);
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const panel = element.querySelector('nav[aria-label="Navegação móvel"]');

    expect(panel).not.toBeNull();
    expect(panel?.textContent?.includes('ADMIN')).toBe(admin);
    expect(panel?.querySelector('a[href="/admin"]') !== null).toBe(admin);
    if (roles) {
      expect(panel?.textContent).toContain('Ana');
      expect(panel?.textContent).toContain('SAIR');
    }
  });

  it('fecha o painel ao escolher um link e navega para o catálogo', async () => {
    const { fixture, element } = await render();
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const catalog = element.querySelector<HTMLAnchorElement>(
      'nav[aria-label="Navegação móvel"] a[href="/catalog"]',
    );
    expect(catalog).not.toBeNull();

    catalog?.click();
    await fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/catalog');
    expect(element.querySelector('nav[aria-label="Navegação móvel"]')).toBeNull();
  });

  it('marca a rota atual também no painel e distingue as duas navegações', async () => {
    const { fixture, element } = await render(['ADMIN']);
    await TestBed.inject(Router).navigateByUrl('/admin');
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const navs = Array.from(element.querySelectorAll('nav'));
    const panel = element.querySelector('nav[aria-label="Navegação móvel"]');

    expect(navs).toHaveLength(2);
    expect(navs[0]?.getAttribute('aria-label')).not.toBe(navs[1]?.getAttribute('aria-label'));
    expect(panel?.querySelector('a[href="/admin"]')?.getAttribute('aria-current')).toBe('page');
    expect(panel?.querySelector('a[href="/"]')?.hasAttribute('aria-current')).toBe(false);
    expect(panel?.querySelector('a[href="/catalog"]')?.hasAttribute('aria-current')).toBe(false);
  });

  it('fecha o painel com Escape e devolve o foco ao botão de menu', async () => {
    const { fixture, element } = await render();
    const toggle = element.querySelector<HTMLButtonElement>('button[aria-controls]');
    toggle?.click();
    await fixture.whenStable();
    const home = element.querySelector<HTMLAnchorElement>(
      'nav[aria-label="Navegação móvel"] a[href="/"]',
    );
    expect(home).not.toBeNull();
    home?.focus();
    expect(document.activeElement).toBe(home);

    home?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(element.querySelector('nav[aria-label="Navegação móvel"]')).toBeNull();
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(toggle);
  });

  it('fecha o painel ao iniciar o login', async () => {
    const { fixture, element } = await render();
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const login = element.querySelector<HTMLButtonElement>(
      'nav[aria-label="Navegação móvel"] button',
    );
    expect(login?.textContent?.trim()).toBe('ENTRAR');

    login?.click();
    await fixture.whenStable();
    expect(element.querySelector('nav[aria-label="Navegação móvel"]')).toBeNull();
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it('fecha o painel ao iniciar o logout', async () => {
    const { fixture, element } = await render(['CUSTOMER']);
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const logout = element.querySelector<HTMLButtonElement>(
      'nav[aria-label="Navegação móvel"] button',
    );
    expect(logout?.textContent?.trim()).toBe('SAIR');

    logout?.click();
    await fixture.whenStable();
    expect(element.querySelector('nav[aria-label="Navegação móvel"]')).toBeNull();
    http.expectOne('/logout').flush(null, {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: '/' },
    });
    expect(assign).toHaveBeenCalledExactlyOnceWith('/');
  });
  it('mostra link acessível e badge com a soma das quantidades no desktop e no mobile', async () => {
    const { fixture, element } = await render(['CUSTOMER']);
    TestBed.inject(Cart).add('p1', 2);
    TestBed.inject(Cart).add('p2');
    element.querySelector<HTMLButtonElement>('button[aria-controls]')?.click();
    await fixture.whenStable();
    const links = element.querySelectorAll('a[aria-label="Carrinho, 3 itens"]');
    expect(links).toHaveLength(2);
    for (const link of links) {
      expect(link.getAttribute('href')).toBe('/cart');
      expect(link.textContent?.trim()).toBe('3');
      expect(link.querySelector('.pi-shopping-cart')).not.toBeNull();
    }
    TestBed.inject(Cart).clear();
    await fixture.whenStable();
    const empty = element.querySelector('a[aria-label="Carrinho, 0 itens"]');
    expect(empty).not.toBeNull();
    expect(empty?.textContent?.trim()).toBe('');
  });
});
