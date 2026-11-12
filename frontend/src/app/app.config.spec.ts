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

  it('registers the cart adapter and public notifications', () => {
    http.expectOne('/bff/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(TestBed.inject(CartStore)).toBeInstanceOf(LocalStorageCartStore);
    expect(TestBed.inject(MessageService)).toBeInstanceOf(MessageService);
  });

  it('loads the session on bootstrap and registers the session expiration interceptor', async () => {
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
    http.expectOne('/api/order').flush({}, { status: 401, statusText: 'Unauthorized' });
    await rejected;
    expect(TestBed.inject(AuthService).user()).toBeNull();
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it.each([401, 503])('allows application initialization after BFF error %i', async (status) => {
    http.expectOne('/bff/me').flush({}, { status, statusText: 'Failure' });

    await expect(TestBed.inject(ApplicationInitStatus).donePromise).resolves.toBeUndefined();
    expect(TestBed.inject(AuthService).user()).toBeNull();
    expect(assign).not.toHaveBeenCalled();
  });
});
