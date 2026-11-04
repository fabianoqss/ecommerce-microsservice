import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AUTH_LOCATION } from '../../auth/auth-location';
import { AuthService } from '../../auth/auth.service';
import { Header } from './header';

@Component({ template: '' })
class RouteStub {}

describe('Header', () => {
  let http: HttpTestingController;
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
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
});
