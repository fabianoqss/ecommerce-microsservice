import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DOCUMENT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AUTH_LOCATION } from './auth-location';
import { AuthService } from './auth.service';
import { AuthUser } from './auth-user';

describe('AuthService', () => {
  const customer: AuthUser = {
    sub: 'cliente-1',
    name: 'Ana Silva',
    email: 'ana@example.com',
    roles: ['CUSTOMER'],
  };
  let auth: AuthService;
  let http: HttpTestingController;
  let document: Document;
  let assign: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AUTH_LOCATION, useValue: { assign } },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    document = TestBed.inject(DOCUMENT);
  });

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
    vi.restoreAllMocks();
  });

  it('starts without an authenticated user or administrator permissions', () => {
    expect(auth.user()).toBeNull();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.isAdmin()).toBe(false);
  });

  it('loads the user once, including concurrent calls', async () => {
    const first = auth.load();
    const second = auth.load();
    const request = http.expectOne('/bff/me');
    expect(request.request.method).toBe('GET');
    request.flush(customer);
    await Promise.all([first, second]);

    expect(auth.user()).toEqual(customer);
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.isAdmin()).toBe(false);
    await auth.load();
    http.expectNone('/bff/me');
  });

  it.each([401, 403, 500, 503])(
    'resolves initialization without a session on HTTP error %i',
    async (status) => {
      const pending = auth.load();
      http.expectOne('/bff/me').flush({ status }, { status, statusText: 'Failure' });

      await expect(pending).resolves.toBeUndefined();
      expect(auth.user()).toBeNull();
      expect(auth.isAuthenticated()).toBe(false);
      await auth.load();
      http.expectNone('/bff/me');
    },
  );

  it('resolves initialization when the network fails', async () => {
    const pending = auth.load();
    http.expectOne('/bff/me').error(new ProgressEvent('error'));

    await expect(pending).resolves.toBeUndefined();
    expect(auth.user()).toBeNull();
  });

  it('recognizes ADMIN and clears all session data on expiration', async () => {
    const pending = auth.load();
    http.expectOne('/bff/me').flush({ ...customer, roles: ['CUSTOMER', 'ADMIN'] });
    await pending;
    expect(auth.isAdmin()).toBe(true);

    auth.clear();

    expect(auth.user()).toBeNull();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.isAdmin()).toBe(false);
  });

  it('starts login with full navigation and prevents duplicate redirects', () => {
    auth.login();
    auth.login();

    expect(assign).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
    http.expectNone('/oauth2/authorization/keycloak');
  });

  it('logs out via POST with CSRF and XHR headers and navigates to the returned Location', () => {
    document.cookie = 'XSRF-TOKEN=token-csrf; Path=/';
    auth.logout();

    const request = http.expectOne('/logout');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('token-csrf');
    request.flush(null, {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: 'http://keycloak/logout?id_token_hint=x' },
    });

    expect(auth.user()).toBeNull();
    expect(assign).toHaveBeenCalledExactlyOnceWith('http://keycloak/logout?id_token_hint=x');
  });

  it('preserves the user and does not navigate when logout fails', async () => {
    const pending = auth.load();
    http.expectOne('/bff/me').flush(customer);
    await pending;

    auth.logout();
    http.expectOne('/logout').flush(null, { status: 403, statusText: 'Forbidden' });

    expect(auth.user()).toEqual(customer);
    expect(assign).not.toHaveBeenCalled();
  });
});
