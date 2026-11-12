import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ApplicationRef } from '@angular/core';
import { MessageService } from 'primeng/api';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { PublicLayout } from './public-layout';

@Component({ template: '<h1>Public page</h1>' })
class PageStub {}

describe('PublicLayout', () => {
  it('shows public notifications with a link to the cart', async () => {
    TestBed.configureTestingModule({
      providers: [MessageService, provideRouter([{ path: '', component: PublicLayout }])],
    });
    const harness = await RouterTestingHarness.create('/');
    TestBed.inject(MessageService).add({
      key: 'cart',
      severity: 'success',
      detail: 'Product added to cart',
      sticky: true,
    });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(harness.routeNativeElement?.textContent).toContain('Product added to cart');
    expect(harness.routeNativeElement?.querySelector('a[href="/cart"]')?.textContent?.trim()).toBe(
      'View cart',
    );
    const clear = vi.spyOn(TestBed.inject(MessageService), 'clear');
    harness.routeNativeElement?.querySelector<HTMLAnchorElement>('a[href="/cart"]')?.click();
    expect(clear).toHaveBeenCalledExactlyOnceWith('cart');
  });

  it('renders the public page inside the container', async () => {
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideRouter([
          {
            path: '',
            component: PublicLayout,
            children: [{ path: '', component: PageStub }],
          },
        ]),
      ],
    });
    const harness = await RouterTestingHarness.create('/');
    const container = harness.routeNativeElement?.querySelector('div');

    expect(container).toBeTruthy();
    expect(container?.querySelector('router-outlet')).toBeTruthy();
    expect(container?.querySelector('h1')?.textContent).toBe('Public page');
  });
});
