import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { MessageService } from 'primeng/api';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../../core/auth/auth.service';
import { AUTH_LOCATION } from '../../../core/auth/auth-location';
import { Cart } from '../../../core/cart/cart';
import { CartStore } from '../../../core/cart/cart-store';
import { LocalStorageCartStore } from '../../../core/cart/local-storage-cart-store';
import { routes } from '../../../app.routes';

@Component({ template: '' })
class Destination {}

const product = {
  id: 'a',
  name: 'Keyboard',
  description: '',
  price: 12.5,
  category: '',
  imageUrl: null,
  version: 0,
};

describe('CartPage', () => {
  let http: HttpTestingController;
  let cart: Cart;
  const login = vi.fn();

  beforeEach(async () => {
    localStorage.clear();
    login.mockReset();
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'checkout', component: Destination }, ...routes]),
        { provide: CartStore, useClass: LocalStorageCartStore },
        { provide: AUTH_LOCATION, useValue: { assign: login } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const pending = TestBed.inject(AuthService).load();
    http
      .expectOne('/bff/me')
      .flush({ sub: 'ana', name: 'Ana', email: 'ana@example.com', roles: ['CUSTOMER'] });
    await pending;
    cart = TestBed.inject(Cart);
  });
  afterEach(() => {
    try {
      http.verify();
    } finally {
      localStorage.clear();
      TestBed.resetTestingModule();
    }
  });

  async function render() {
    const harness = await RouterTestingHarness.create('/cart');
    TestBed.tick();
    const element = harness.routeNativeElement;
    if (!element) throw new Error('Page missing');
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function checkout(element: HTMLElement) {
    const button = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Checkout'),
    );
    if (!button) throw new Error('Checkout button missing');
    return button;
  }

  it('shows an empty state with a catalog link', async () => {
    const { element } = await render();
    expect(element.textContent).toContain('Your cart is empty');
    expect(
      Array.from(element.querySelectorAll('a'))
        .find((a) => a.textContent?.includes('Browse catalog'))
        ?.getAttribute('href'),
    ).toBe('/catalog');
    http.expectNone((r) => r.url.startsWith('/api/'));
    expect(document.title).toBe('Cart | DS Catalog');
  });

  it('requires authentication when navigating directly to the cart', async () => {
    TestBed.inject(AuthService).clear();
    await RouterTestingHarness.create('/cart');
    expect(login).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
    http.expectNone((r) => r.url.startsWith('/api/'));
  });

  it('shows loading, two lines and subtotal and navigates to checkout', async () => {
    cart.add('a', 2);
    cart.add('b', 3);
    const { harness, element } = await render();
    expect(element.querySelector('p-skeleton')).not.toBeNull();
    expect(checkout(element).disabled).toBe(true);
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse', price: 10 });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    expect(element.textContent).toContain('Keyboard');
    expect(element.textContent).toContain('Mouse');
    expect(element.textContent).toContain('R$25.00');
    expect(element.textContent).toContain('R$30.00');
    expect(element.querySelector('aside')?.textContent).toContain('R$55.00');
    expect(checkout(element).disabled).toBe(false);
    checkout(element).click();
    await settle(harness);
    expect(TestBed.inject(Router).url).toBe('/checkout');
  });

  it('changes quantity and removes through the presentation component', async () => {
    cart.add('a', 2);
    const { harness, element } = await render();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(harness);
    const setQuantity = vi.spyOn(cart, 'setQuantity');
    const remove = vi.spyOn(cart, 'remove');
    const input = element.querySelector('input');
    if (!input) throw new Error('Quantity missing');
    input.value = '3';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('blur'));
    await settle(harness);
    expect(setQuantity).toHaveBeenCalledWith('a', 3);
    expect(element.querySelector('aside')?.textContent).toContain('R$37.50');
    http.expectNone((r) => r.url.startsWith('/api/'));
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Remove'))
      ?.click();
    await settle(harness);
    expect(remove).toHaveBeenCalledWith('a');
    expect(element.textContent).toContain('Your cart is empty');
  });

  it.each([
    ['Home', 1, 'R$12.50'],
    ['End', 5, 'R$62.50'],
  ] as const)(
    'synchronizes quantity and subtotal with the %s key',
    async (key, quantity, subtotal) => {
      cart.add('a', 2);
      const { harness, element } = await render();
      http.expectOne('/api/product/a').flush(product);
      http
        .expectOne('/api/inventory?skuCode=a')
        .flush([{ skuCode: 'a', quantity: 5, inStock: true }]);
      await settle(harness);
      const input = element.querySelector('input');
      if (!input) throw new Error('Quantity missing');
      input.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      input.dispatchEvent(new Event('blur'));
      await settle(harness);
      expect(cart.items()).toEqual([{ productId: 'a', quantity }]);
      expect(input.value).toBe(String(quantity));
      expect(element.querySelector('aside')?.textContent).toContain(subtotal);
      http.expectNone((request) => request.url.startsWith('/api/'));
    },
  );

  it('preserves the other line, its focus and subtotal when removing one of two products without requests', async () => {
    cart.add('a', 2);
    cart.add('b', 3);
    const { harness, element } = await render();
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse', price: 10 });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    document.body.appendChild(harness.fixture.nativeElement);
    const remaining = element.querySelector('article[aria-label="Mouse"]');
    const input = remaining?.querySelector('input');
    input?.focus();
    expect(document.activeElement).toBe(input);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.getAttribute('aria-label') === 'Remove Keyboard')
      ?.click();
    TestBed.tick();
    harness.detectChanges();
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('article[aria-label="Mouse"]')).toBe(remaining);
    expect(input).toBeDefined();
    expect(element.querySelector('article[aria-label="Mouse"] input')).toBe(input);
    expect(document.activeElement).toBe(input);
    expect(element.querySelector('aside')?.textContent).toContain('R$30.00');
    http.expectNone((request) => request.url.startsWith('/api/'));
  });

  it('shows valid lines, partial subtotal and a removable product alongside ProblemDetail', async () => {
    cart.add('a', 2);
    cart.add('b');
    const { harness, element } = await render();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/product/b')
      .flush(
        { detail: 'Product temporarily unavailable.' },
        { status: 503, statusText: 'Unavailable' },
      );
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    expect(element.querySelector('article[aria-label="Keyboard"]')?.textContent).toContain(
      'R$25.00',
    );
    expect(element.textContent).toContain('Unable to load this product.');
    expect(element.textContent).toContain('Product temporarily unavailable.');
    expect(element.querySelector('aside')?.textContent).toContain('R$25.00');
    expect(checkout(element).disabled).toBe(true);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Try again'))
      ?.click();
    TestBed.tick();
    harness.detectChanges();
    expect(element.textContent).not.toContain('Product temporarily unavailable.');
    expect(element.querySelector('article[aria-label="Keyboard"]')).not.toBeNull();
    expect(element.querySelector('p-skeleton')).toBeNull();
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse' });
    http.expectNone((request) => request.url.startsWith('/api/'));
    await settle(harness);
    expect(checkout(element).disabled).toBe(false);
  });

  it.each(['unavailable', 'out-of-stock', 'insufficient-stock'])(
    'blocks checkout for %s',
    async (status) => {
      cart.add('a', 2);
      const { harness, element } = await render();
      const request = http.expectOne('/api/product/a');
      if (status === 'unavailable') request.flush({}, { status: 404, statusText: 'Not Found' });
      else request.flush(product);
      http.expectOne('/api/inventory?skuCode=a').flush([
        {
          skuCode: 'a',
          quantity: status === 'out-of-stock' ? 0 : 1,
          inStock: status !== 'out-of-stock',
        },
      ]);
      await settle(harness);
      expect(element.textContent).toContain(
        status === 'unavailable'
          ? 'Product unavailable'
          : status === 'out-of-stock'
            ? 'Out of stock'
            : 'Only 1 in stock',
      );
      expect(checkout(element).disabled).toBe(true);
      expect(element.querySelector('aside')?.textContent).toContain('R$0.00');
    },
  );

  it.each(['product', 'inventory'])(
    'allows removing items even during a %s failure',
    async (failing) => {
      cart.add('a');
      const { harness, element } = await render();
      const productRequest = http.expectOne('/api/product/a');
      const inventoryRequest = http.expectOne('/api/inventory?skuCode=a');
      if (failing === 'product') {
        productRequest.flush(
          { detail: 'Catalog unavailable.' },
          { status: 503, statusText: 'Unavailable' },
        );
        inventoryRequest.flush([]);
      } else {
        productRequest.flush(product);
        inventoryRequest.flush(
          { detail: 'Stock unavailable.' },
          { status: 503, statusText: 'Unavailable' },
        );
      }
      await settle(harness);
      expect(checkout(element).disabled).toBe(true);
      const remove = Array.from(element.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Remove'),
      );
      expect(remove).toBeDefined();
      remove?.click();
      await settle(harness);
      expect(cart.items()).toEqual([]);
      expect(element.textContent).toContain('Your cart is empty');
    },
  );

  it('shows ProblemDetail and retries after a product failure', async () => {
    cart.add('a');
    const { harness, element } = await render();
    http
      .expectOne('/api/product/a')
      .flush({ detail: 'Catalog unavailable.' }, { status: 503, statusText: 'Unavailable' });
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Catalog unavailable.');
    expect(checkout(element).disabled).toBe(true);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Try again'))
      ?.click();
    TestBed.tick();
    http.expectOne('/api/product/a').flush(product);
    http.expectNone((request) => request.url === '/api/inventory');
    await settle(harness);
    expect(element.querySelector('p-message')).toBeNull();
    expect(checkout(element).disabled).toBe(false);
  });
});
