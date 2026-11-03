import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AUTH_LOCATION } from './auth-location';
import { AuthUser } from './auth-user';

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly location = inject(AUTH_LOCATION);
  private readonly currentUser = signal<AuthUser | null>(null);
  private loadPromise?: Promise<void>;
  private loginStarted = false;

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.user() !== null);
  readonly isAdmin = computed(() => this.user()?.roles.includes('ADMIN') ?? false);

  load(): Promise<void> {
    this.loadPromise ??= firstValueFrom(
      this.http.get<AuthUser>('/bff/me', { timeout: 10_000 }),
    ).then(
      (user) => this.currentUser.set(user),
      () => this.clear(),
    );
    return this.loadPromise;
  }

  clear(): void {
    this.currentUser.set(null);
  }

  login(): void {
    if (this.loginStarted) {
      return;
    }
    this.loginStarted = true;
    this.location.assign('/oauth2/authorization/keycloak');
  }

  logout(): void {
    this.http
      .post('/logout', null, {
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
        observe: 'response',
      })
      .subscribe({
        next: (response) => {
          this.clear();
          this.location.assign(response.headers.get('Location') ?? '/');
        },
        error: () => undefined,
      });
  }
}
