import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, ElementRef, inject, signal, viewChild } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../auth/auth.service';

@Component({
  imports: [NgTemplateOutlet, RouterLink, RouterLinkActive],
  selector: 'app-header',
  templateUrl: './header.html',
  host: {
    '(document:keydown.escape)': 'closeMenu(true)',
  },
})
export class Header {
  protected readonly auth = inject(AuthService);
  protected readonly menuOpen = signal(false);
  protected readonly navigationLinks = computed(() => [
    { path: '/', label: 'HOME' },
    { path: '/catalog', label: 'CATÁLOGO' },
    ...(this.auth.isAdmin() ? [{ path: '/admin', label: 'ADMIN' }] : []),
  ]);
  protected readonly firstName = computed(() => {
    const user = this.auth.user();
    return user?.name?.trim().split(/\s+/)[0] || user?.email || '';
  });
  private readonly menuButton = viewChild<ElementRef<HTMLButtonElement>>('menuButton');

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  protected closeMenu(restoreFocus = false): void {
    if (!this.menuOpen()) {
      return;
    }
    this.menuOpen.set(false);
    if (restoreFocus) {
      this.menuButton()?.nativeElement.focus();
    }
  }

  protected login(): void {
    this.closeMenu();
    this.auth.login();
  }

  protected logout(): void {
    this.closeMenu();
    this.auth.logout();
  }
}
