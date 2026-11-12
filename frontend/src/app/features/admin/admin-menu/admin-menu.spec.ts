import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AdminMenu } from './admin-menu';

@Component({ template: '' })
class RouteStub {}

describe('AdminMenu', () => {
  async function render(url = '/admin/products') {
    TestBed.configureTestingModule({
      imports: [AdminMenu],
      providers: [
        provideRouter([
          { path: 'admin/products', component: RouteStub },
          { path: '', component: RouteStub },
        ]),
      ],
    });
    const fixture = TestBed.createComponent(AdminMenu);
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('marks Products as the current page at /admin/products', async () => {
    const { fixture, element } = await render();
    const nav = element.querySelector('nav[aria-label="Administration"]');
    const products = nav?.querySelector('a[href="/admin/products"]');

    expect(products?.textContent?.trim()).toBe('Products');
    expect(products?.getAttribute('aria-current')).toBe('page');
    expect(products?.classList.contains('bg-primary/30')).toBe(true);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    expect(products?.hasAttribute('aria-current')).toBe(false);
    expect(products?.classList.contains('bg-primary/30')).toBe(false);
  });

  it('shows Categories and Users as disabled and excluded from keyboard navigation', async () => {
    const { element } = await render();
    const items = Array.from(element.querySelectorAll('nav ul > li'));
    const disabled = Array.from(element.querySelectorAll('[aria-disabled="true"]'));

    expect(items.map((item) => item.textContent?.trim())).toEqual([
      'Products',
      'Categories',
      'Users',
    ]);
    expect(disabled.map((item) => item.textContent?.trim())).toEqual(['Categories', 'Users']);
    for (const item of disabled) {
      expect(item.getAttribute('role')).toBe('link');
      expect(item.hasAttribute('href')).toBe(false);
      expect(item.hasAttribute('tabindex')).toBe(false);
    }
    expect(element.querySelectorAll('a[href]')).toHaveLength(1);
  });
});
