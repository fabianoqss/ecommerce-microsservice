import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { CartStore } from './core/cart/cart-store';
import { LocalStorageCartStore } from './core/cart/local-storage-cart-store';
import { MessageService } from 'primeng/api';
import { appConfig } from './app.config';
import { AUTH_LOCATION } from './core/auth/auth-location';
import { AuthService } from './core/auth/auth.service';

vi.mock('primeng/config', () => ({ providePrimeNG: () => [] }));

describe('appConfig', () => {
  let http: HttpTestingController;
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        { provide: AUTH_LOCATION, useValue: { assign } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('registra o adaptador do carrinho e as notificações públicas', () => {
    http.expectOne('/bff/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(TestBed.inject(CartStore)).toBeInstanceOf(LocalStorageCartStore);
    expect(TestBed.inject(MessageService)).toBeInstanceOf(MessageService);
  });

  it('carrega a sessão no bootstrap e registra o interceptor de sessão expirada', async () => {
    http.expectOne('/bff/me').flush({
      sub: 'admin-1',
      name: 'Ana Silva',
      email: 'ana@example.com',
      roles: ['ADMIN'],
    });
    await TestBed.inject(ApplicationInitStatus).donePromise;
    expect(TestBed.inject(AuthService).isAdmin()).toBe(true);

    const result = firstValueFrom(TestBed.inject(HttpClient).get('/api/order'));
    const rejected = expect(result).rejects.toMatchObject({ status: 401 });
    http.expectOne('/api/order').flush({}, { status: 401, statusText: 'Não autorizado' });
    await rejected;
    expect(TestBed.inject(AuthService).user()).toBeNull();
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it.each([401, 503])('permite inicializar a aplicação após erro %i no BFF', async (status) => {
    http.expectOne('/bff/me').flush({}, { status, statusText: 'Falha' });

    await expect(TestBed.inject(ApplicationInitStatus).donePromise).resolves.toBeUndefined();
    expect(TestBed.inject(AuthService).user()).toBeNull();
    expect(assign).not.toHaveBeenCalled();
  });
});
