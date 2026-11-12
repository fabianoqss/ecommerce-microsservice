import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { AuthService } from './auth.service';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  it.each([true, false])('allows access only with a session: %s', (authenticated) => {
    const login = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { isAuthenticated: signal(authenticated), login } },
      ],
    });
    const result = TestBed.runInInjectionContext(() =>
      authGuard(new ActivatedRouteSnapshot(), { url: '/cart' } as RouterStateSnapshot),
    );
    expect(result).toBe(authenticated);
    expect(login).toHaveBeenCalledTimes(authenticated ? 0 : 1);
  });
});
