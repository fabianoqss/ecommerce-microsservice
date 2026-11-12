import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AUTH_LOCATION } from './auth-location';
import { AuthService } from './auth.service';
import { sessionExpiredInterceptor } from './session-expired.interceptor';

describe('sessionExpiredInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let auth: AuthService;
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([sessionExpiredInterceptor])),
        provideHttpClientTesting(),
        { provide: AUTH_LOCATION, useValue: { assign } },
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  async function loadUser(): Promise<void> {
    const pending = auth.load();
    http.expectOne('/bff/me').flush({
      sub: 'admin-1',
      name: 'Ana Silva',
      email: 'ana@example.com',
      roles: ['ADMIN'],
    });
    await pending;
  }

  it('clears the session, starts login and propagates the /api/** 401 error', async () => {
    await loadUser();
    const result = firstValueFrom(client.get('/api/order?size=8'));
    const rejected = expect(result).rejects.toMatchObject({
      status: 401,
      error: { detail: 'Session expired.' },
    });
    http
      .expectOne('/api/order?size=8')
      .flush({ detail: 'Session expired.' }, { status: 401, statusText: 'Unauthorized' });
    await rejected;

    expect(auth.user()).toBeNull();
    expect(auth.isAdmin()).toBe(false);
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it.each(['/bff/me', '/apiary/product', 'https://external.example/api/product'])(
    'does not start login for 401 on %s',
    async (url) => {
      await loadUser();
      const result = firstValueFrom(client.get(url));
      const rejected = expect(result).rejects.toMatchObject({ status: 401 });
      http.expectOne(url).flush({}, { status: 401, statusText: 'Unauthorized' });
      await rejected;

      expect(auth.isAuthenticated()).toBe(true);
      expect(assign).not.toHaveBeenCalled();
    },
  );

  it.each([403, 404, 500, 503])(
    'propagates error %i without clearing the session',
    async (status) => {
      await loadUser();
      const result = firstValueFrom(client.get('/api/product'));
      const rejected = expect(result).rejects.toMatchObject({
        status,
        error: { detail: 'API failure.' },
      });
      http
        .expectOne('/api/product')
        .flush({ detail: 'API failure.' }, { status, statusText: 'Failure' });
      await rejected;

      expect(auth.isAuthenticated()).toBe(true);
      expect(assign).not.toHaveBeenCalled();
    },
  );

  it('propagates network failures without starting login', async () => {
    const result = firstValueFrom(client.get('/api/product'));
    const rejected = expect(result).rejects.toBeInstanceOf(HttpErrorResponse);
    http.expectOne('/api/product').error(new ProgressEvent('error'));
    await rejected;

    expect(assign).not.toHaveBeenCalled();
  });

  it('passes through successful responses', async () => {
    const result = firstValueFrom(client.get('/api/product'));
    http.expectOne('/api/product').flush({ content: [] });

    await expect(result).resolves.toEqual({ content: [] });
    expect(assign).not.toHaveBeenCalled();
  });
});
