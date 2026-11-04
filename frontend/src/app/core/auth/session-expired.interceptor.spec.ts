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

  it('limpa a sessão, inicia login e repassa o erro 401 de /api/**', async () => {
    await loadUser();
    const result = firstValueFrom(client.get('/api/order?size=8'));
    const rejected = expect(result).rejects.toMatchObject({
      status: 401,
      error: { detail: 'Sessão expirada.' },
    });
    http
      .expectOne('/api/order?size=8')
      .flush({ detail: 'Sessão expirada.' }, { status: 401, statusText: 'Não autorizado' });
    await rejected;

    expect(auth.user()).toBeNull();
    expect(auth.isAdmin()).toBe(false);
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it.each(['/bff/me', '/apiary/product', 'https://externo.example/api/product'])(
    'não inicia login para 401 em %s',
    async (url) => {
      await loadUser();
      const result = firstValueFrom(client.get(url));
      const rejected = expect(result).rejects.toMatchObject({ status: 401 });
      http.expectOne(url).flush({}, { status: 401, statusText: 'Não autorizado' });
      await rejected;

      expect(auth.isAuthenticated()).toBe(true);
      expect(assign).not.toHaveBeenCalled();
    },
  );

  it.each([403, 404, 500, 503])('repassa o erro %i sem limpar a sessão', async (status) => {
    await loadUser();
    const result = firstValueFrom(client.get('/api/product'));
    const rejected = expect(result).rejects.toMatchObject({
      status,
      error: { detail: 'Falha na API.' },
    });
    http
      .expectOne('/api/product')
      .flush({ detail: 'Falha na API.' }, { status, statusText: 'Falha' });
    await rejected;

    expect(auth.isAuthenticated()).toBe(true);
    expect(assign).not.toHaveBeenCalled();
  });

  it('repassa falhas de rede sem iniciar login', async () => {
    const result = firstValueFrom(client.get('/api/product'));
    const rejected = expect(result).rejects.toBeInstanceOf(HttpErrorResponse);
    http.expectOne('/api/product').error(new ProgressEvent('error'));
    await rejected;

    expect(assign).not.toHaveBeenCalled();
  });

  it('repassa respostas bem-sucedidas', async () => {
    const result = firstValueFrom(client.get('/api/product'));
    http.expectOne('/api/product').flush({ content: [] });

    await expect(result).resolves.toEqual({ content: [] });
    expect(assign).not.toHaveBeenCalled();
  });
});
