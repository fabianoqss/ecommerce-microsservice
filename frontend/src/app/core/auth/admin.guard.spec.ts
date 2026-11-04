import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { adminGuard } from './admin.guard';
import { AUTH_LOCATION } from './auth-location';
import { AuthService } from './auth.service';

describe('adminGuard', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AUTH_LOCATION, useValue: { assign } },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const runGuard = () =>
    TestBed.runInInjectionContext(() => adminGuard({}, [], new ActivatedRouteSnapshot()));

  async function loadUser(roles: string[]): Promise<void> {
    const pending = auth.load();
    http.expectOne('/bff/me').flush({
      sub: 'usuario-1',
      name: 'Ana Silva',
      email: 'ana@example.com',
      roles,
    });
    await pending;
  }

  it('inicia login e recusa o acesso sem sessão', () => {
    expect(runGuard()).toBe(false);
    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
  });

  it('devolve UrlTree para a home quando o usuário não é ADMIN', async () => {
    await loadUser(['CUSTOMER']);

    const result = runGuard();

    expect(result).toBeInstanceOf(UrlTree);
    if (!(result instanceof UrlTree)) {
      throw new Error('Era esperado um redirecionamento para a home.');
    }
    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/');
    expect(assign).not.toHaveBeenCalled();
  });

  it('permite o acesso de ADMIN', async () => {
    await loadUser(['CUSTOMER', 'ADMIN']);

    expect(runGuard()).toBe(true);
    expect(assign).not.toHaveBeenCalled();
  });
});
