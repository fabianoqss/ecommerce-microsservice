import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MessageService } from 'primeng/api';
import { routes } from '../../../app.routes';
import { AUTH_LOCATION } from '../../../core/auth/auth-location';
import { AuthService } from '../../../core/auth/auth.service';
import { Cart } from '../../../core/cart/cart';
import { CartStore } from '../../../core/cart/cart-store';
import { LocalStorageCartStore } from '../../../core/cart/local-storage-cart-store';

@Component({ template: '<h1>Confirmation destination</h1>' })
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
const order = {
  id: 'order-1',
  status: 'PENDING',
  totalValue: 25,
  orderTime: '2026-11-08T10:30:00',
  items: [{ productId: 'a', productName: 'Keyboard', quantity: 2, priceAtPurchase: 12.5 }],
};

describe('Checkout', () => {
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
        provideRouter([{ path: 'orders/:id/confirmation', component: Destination }, ...routes]),
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
    const harness = await RouterTestingHarness.create('/checkout');
    TestBed.tick();
    const element = harness.routeNativeElement;
    if (!element) throw new Error('Page missing');
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function button(element: HTMLElement, label = 'Place order') {
    const result = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes(label),
    );
    if (!result) throw new Error('Button missing: ' + label);
    return result;
  }

  async function ready() {
    cart.add('a', 2);
    const result = await render();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(result.harness);
    return result;
  }

  it('shows an empty state with a catalog link without fetching products', async () => {
    const { element } = await render();
    expect(element.textContent).toContain('Your cart is empty');
    expect(element.querySelector('a[href="/catalog"]')?.textContent).toContain('Browse catalog');
    expect(element.querySelector('button')).toBeNull();
    http.expectNone((request) => request.url.startsWith('/api/'));
    expect(document.title).toBe('Order | DS Catalog');
  });

  it('requires authentication when navigating directly to checkout', async () => {
    TestBed.inject(AuthService).clear();
    await RouterTestingHarness.create('/checkout');
    expect(login).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
    http.expectNone((request) => request.url.startsWith('/api/'));
  });

  it('loads products and stock and shows a read-only review and total', async () => {
    cart.add('a', 2);
    cart.add('b', 3);
    const { harness, element } = await render();
    expect(element.querySelector('p-skeleton')).not.toBeNull();
    expect(button(element).disabled).toBe(true);
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse', price: 10 });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    const lines = element.querySelector('ul');
    expect(lines?.textContent).toContain('Keyboard');
    expect(lines?.textContent).toContain('Mouse');
    expect(lines?.textContent).toContain('Quantity: 2');
    expect(lines?.textContent).toContain('Quantity: 3');
    expect(lines?.textContent).toContain('R$12.50');
    expect(lines?.textContent).toContain('R$25.00');
    expect(lines?.textContent).toContain('R$30.00');
    expect(element.querySelector('aside')?.textContent).toContain('R$55.00');
    expect(element.querySelector('input, p-inputnumber')).toBeNull();
    expect(button(element).disabled).toBe(false);
    expect(element.querySelector('a[href="/cart"]')?.textContent).toContain('Back to cart');
  });

  it.each([
    ['unavailable', 'Product unavailable'],
    ['out-of-stock', 'Out of stock'],
    ['insufficient-stock', 'Only 1 in stock'],
    ['error', 'Unable to load this product.'],
  ])('blocks submission and identifies the line with status %s', async (status, message) => {
    cart.add('a', 2);
    const { harness, element } = await render();
    const request = http.expectOne('/api/product/a');
    if (status === 'unavailable') request.flush({}, { status: 404, statusText: 'Not Found' });
    else if (status === 'error')
      request.flush({ detail: 'Catalog unavailable.' }, { status: 503, statusText: 'Unavailable' });
    else request.flush(product);
    http.expectOne('/api/inventory?skuCode=a').flush([
      {
        skuCode: 'a',
        quantity: status === 'out-of-stock' ? 0 : 1,
        inStock: status !== 'out-of-stock',
      },
    ]);
    await settle(harness);
    expect(element.textContent).toContain(message);
    expect(button(element).disabled).toBe(true);
    button(element).dispatchEvent(new MouseEvent('click', { bubbles: true }));
    http.expectNone('/api/order');
    expect(cart.totalQuantity()).toBe(2);
  });

  it('shows loading ProblemDetail and allows retrying', async () => {
    cart.add('a', 2);
    const { harness, element } = await render();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush({ detail: 'Stock unavailable.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Stock unavailable.');
    expect(button(element).disabled).toBe(true);
    button(element, 'Try again').click();
    TestBed.tick();
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(harness);
    expect(button(element).disabled).toBe(false);
    expect(element.querySelector('p-message')).toBeNull();
  });

  it('sends only IDs and quantities with a UUID v4 and prevents double clicks before rendering', async () => {
    const { harness, element } = await ready();
    const submit = button(element);
    submit.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    submit.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const request = http.expectOne('/api/order');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ items: [{ productId: 'a', quantity: 2 }] });
    expect(request.request.headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    harness.detectChanges();
    expect(button(element).disabled).toBe(true);
    expect(cart.totalQuantity()).toBe(2);
    request.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
    expect(cart.items()).toEqual([]);
    expect(localStorage.getItem('cart:ana')).toBe('[]');
    expect(TestBed.inject(Router).url).toBe('/orders/order-1/confirmation');
  });

  it.each([400, 409, 500, 503, 0])(
    'preserves the cart and key after HTTP %s and allows retrying',
    async (status) => {
      const { harness, element } = await ready();
      button(element).click();
      const first = http.expectOne('/api/order');
      const key = first.request.headers.get('Idempotency-Key');
      if (status === 0) first.error(new ProgressEvent('error'));
      else first.flush({ detail: 'Unable to reserve stock.' }, { status, statusText: 'Error' });
      await settle(harness);
      expect(element.querySelector('p-message')?.textContent).toContain(
        status === 0 ? 'Unable to place your order. Please try again.' : 'Unable to reserve stock.',
      );
      expect(cart.items()).toEqual([{ productId: 'a', quantity: 2 }]);
      expect(button(element).disabled).toBe(false);
      expect(TestBed.inject(Router).url).toBe('/checkout');
      button(element).click();
      const retry = http.expectOne('/api/order');
      expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
      expect(retry.request.body).toEqual({ items: [{ productId: 'a', quantity: 2 }] });
      retry.flush(order, { status: 201, statusText: 'Created' });
      await settle(harness);
      expect(cart.items()).toEqual([]);
    },
  );

  it('changes the key after 422 and requests review while preserving the cart', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const first = http.expectOne('/api/order');
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(
      { detail: 'Key already used.' },
      { status: 422, statusText: 'Unprocessable Content' },
    );
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain(
      'Your cart has changed. Review it and try again.',
    );
    expect(cart.totalQuantity()).toBe(2);
    button(element).click();
    const retry = http.expectOne('/api/order');
    expect(retry.request.headers.get('Idempotency-Key')).not.toBe(key);
    retry.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
  });

  it('changes the key when the cart snapshot changes before the next click', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const first = http.expectOne('/api/order');
    const key = first.request.headers.get('Idempotency-Key');
    first.flush({ detail: 'Try again.' }, { status: 409, statusText: 'Conflict' });
    await settle(harness);
    cart.setQuantity('a', 3);
    button(element).dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const retry = http.expectOne('/api/order');
    expect(retry.request.headers.get('Idempotency-Key')).not.toBe(key);
    expect(retry.request.body).toEqual({ items: [{ productId: 'a', quantity: 3 }] });
    retry.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
  });

  it('does not clear the cart or navigate after an unexpected 200 response', async () => {
    const { harness, element } = await ready();
    button(element).click();
    http.expectOne('/api/order').flush(order);
    await settle(harness);
    expect(cart.totalQuantity()).toBe(2);
    expect(TestBed.inject(Router).url).toBe('/checkout');
    expect(element.querySelector('p-message')?.textContent).toContain(
      'Unable to place your order. Please try again.',
    );
    expect(button(element).disabled).toBe(false);
  });

  it.each([409, 0])(
    'reuses the key after HTTP %s when leaving and recreating checkout',
    async (status) => {
      const { harness, element } = await ready();
      button(element).click();
      const first = http.expectOne('/api/order');
      const key = first.request.headers.get('Idempotency-Key');
      if (status === 0) first.error(new ProgressEvent('error'));
      else first.flush({ detail: 'Processing.' }, { status, statusText: 'Conflict' });
      await settle(harness);
      await harness.navigateByUrl('/orders/fora/confirmation');
      await harness.navigateByUrl('/checkout');
      TestBed.tick();
      http.expectOne('/api/product/a').flush(product);
      http
        .expectOne('/api/inventory?skuCode=a')
        .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
      await settle(harness);
      const recreated = harness.routeNativeElement;
      if (!recreated) throw new Error('Page missing');
      button(recreated).click();
      const retry = http.expectOne('/api/order');
      retry.flush(order, { status: 201, statusText: 'Created' });
      expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
      await settle(harness);
    },
  );

  it('removes only submitted quantities when the cart changes during POST', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const request = http.expectOne('/api/order');
    cart.add('a', 3);
    cart.add('b', 4);
    expect(request.request.body).toEqual({ items: [{ productId: 'a', quantity: 2 }] });
    request.flush(order, { status: 201, statusText: 'Created' });
    expect(cart.items()).toEqual([
      { productId: 'a', quantity: 3 },
      { productId: 'b', quantity: 4 },
    ]);
    expect(JSON.parse(localStorage.getItem('cart:ana') ?? 'null')).toEqual([
      { productId: 'a', quantity: 3 },
      { productId: 'b', quantity: 4 },
    ]);
    await settle(harness);
  });

  it('processes 201 after destroying checkout without navigating to confirmation', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const request = http.expectOne('/api/order');
    await harness.navigateByUrl('/orders/fora/confirmation');
    request.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
    expect(cart.items()).toEqual([]);
    expect(localStorage.getItem('cart:ana')).toBe('[]');
    expect(TestBed.inject(Router).url).toBe('/orders/fora/confirmation');
  });

  it('reuses the key when recreating checkout while the first POST is pending', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const first = http.expectOne('/api/order');
    const key = first.request.headers.get('Idempotency-Key');
    await harness.navigateByUrl('/orders/fora/confirmation');
    await harness.navigateByUrl('/checkout');
    TestBed.tick();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(harness);
    const recreated = harness.routeNativeElement;
    if (!recreated) throw new Error('Page missing');
    button(recreated).click();
    const retry = http.expectOne('/api/order');
    retry.flush({ detail: 'Processing.' }, { status: 409, statusText: 'Conflict' });
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    first.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
    expect(cart.items()).toEqual([]);
    expect(TestBed.inject(Router).url).toBe('/checkout');
  });

  it('shows confirmation in progress without an empty cart while navigation waits', async () => {
    const { harness, element } = await ready();
    let releaseConfirmation: () => void = () => undefined;
    const confirmation = new Promise<typeof Destination>((resolve) => {
      releaseConfirmation = () => resolve(Destination);
    });
    TestBed.inject(Router).resetConfig([
      { path: 'orders/:id/confirmation', loadComponent: () => confirmation },
      ...routes,
    ]);
    try {
      button(element).click();
      http.expectOne('/api/order').flush(order, { status: 201, statusText: 'Created' });
      harness.detectChanges();
      expect(element.querySelector('[role="status"]')?.textContent).toContain(
        'Order confirmed. Opening confirmation…',
      );
      expect(element.textContent).not.toContain('Your cart is empty');
      expect(element.querySelector('button')).toBeNull();
    } finally {
      releaseConfirmation();
      await settle(harness);
    }
  });

  it.each([false, true])(
    'uses a new key after 201 for the same snapshot with checkout destroyed: %s',
    async (destroyed) => {
      const { harness, element } = await ready();
      button(element).click();
      const first = http.expectOne('/api/order');
      const key = first.request.headers.get('Idempotency-Key');
      if (destroyed) await harness.navigateByUrl('/orders/fora/confirmation');
      first.flush(order, { status: 201, statusText: 'Created' });
      await settle(harness);
      cart.add('a', 2);
      await harness.navigateByUrl('/checkout');
      TestBed.tick();
      http.expectOne('/api/product/a').flush(product);
      http
        .expectOne('/api/inventory?skuCode=a')
        .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
      await settle(harness);
      const recreated = harness.routeNativeElement;
      if (!recreated) throw new Error('Page missing');
      button(recreated).click();
      const next = http.expectOne('/api/order');
      next.flush(order, { status: 201, statusText: 'Created' });
      expect(next.request.headers.get('Idempotency-Key')).not.toBe(key);
      await settle(harness);
    },
  );

  it.each([
    [404, 'Product unavailable'],
    [503, 'Product'],
  ])('uniquely identifies products without data after HTTP %s', async (status, label) => {
    cart.add('a');
    cart.add('b');
    const { harness, element } = await render();
    for (const id of ['a', 'b']) {
      http.expectOne('/api/product/' + id).flush({}, { status, statusText: 'Error' });
    }
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    for (const id of ['a', 'b']) {
      const article = element.querySelector(`article[aria-label="${label} (${id})"]`);
      expect(article).not.toBeNull();
      expect(article?.querySelector('h2')?.textContent).toBe(`${label} (${id})`);
      if (status === 503) {
        expect(article?.textContent).toContain('Unable to load this product.');
        expect(article?.textContent).not.toContain('Product unavailable');
      }
    }
  });
});
