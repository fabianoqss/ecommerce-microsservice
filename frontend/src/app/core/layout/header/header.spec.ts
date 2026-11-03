import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Header } from './header';

@Component({ template: '' })
class RouteStub {}

describe('Header', () => {
  it('marca somente o link da rota atual como ativo', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', component: RouteStub },
          { path: 'catalog', component: RouteStub },
          { path: 'catalog/:id', component: RouteStub },
        ]),
      ],
    });
    const fixture = TestBed.createComponent(Header);
    await TestBed.inject(Router).navigateByUrl('/catalog');
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
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
