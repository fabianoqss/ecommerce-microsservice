import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MessageService } from 'primeng/api';
import { routes } from '../../../app.routes';
import { AUTH_LOCATION } from '../../../core/auth/auth-location';
import { AuthService } from '../../../core/auth/auth.service';
import { CartStore } from '../../../core/cart/cart-store';
import { LocalStorageCartStore } from '../../../core/cart/local-storage-cart-store';
import { Order } from '../../../shared/data-access/order/order';

const order: Order = {
  id: 'd715a7c9-d942-4c65-92eb-704c797dc6ee',
  status: 'PENDING',
  totalValue: 42.75,
  orderTime: '2026-03-26T14:30:00',
  items: [
    { productId: 'a', productName: 'Purchased keyboard', quantity: 2, priceAtPurchase: 12.5 },
    { productId: 'b', productName: 'Purchased mouse', quantity: 1, priceAtPurchase: 15 },
  ],
};
const url = '/orders/' + order.id + '/confirmation';

describe('OrderConfirmation', () => {
  let http: HttpTestingController;
  const login = vi.fn();

  beforeEach(async () => {
    localStorage.clear();
    login.mockReset();
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes, withComponentInputBinding()),
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
  });

  afterEach(() => {
    try {
      http.expectNone((request) => request.url.startsWith('/api/product'));
      http.verify();
    } finally {
      localStorage.clear();
      TestBed.resetTestingModule();
    }
  });

  async function render(path = url) {
    const harness = await RouterTestingHarness.create(path);
    TestBed.tick();
    const element = harness.routeNativeElement;
    if (!element) throw new Error('Page missing');
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  it('shows accessible loading while fetching the order', async () => {
    const { harness, element } = await render();
    expect(element.querySelector('p-skeleton')).not.toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    expect(element.querySelector('[role="status"]')?.textContent).toContain('Loading order.');
    const request = http.expectOne('/api/order/' + order.id);
    expect(request.request.method).toBe('GET');
    request.flush(order);
    await settle(harness);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('false');
  });

  it('shows the number, item snapshots, server total and return to catalog', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush(order);
    await settle(harness);
    expect(document.title).toBe('Order confirmed | DS Catalog');
    expect(element.querySelector('h1')?.textContent?.trim()).toBe('Order confirmed!');
    expect(element.textContent).toContain(order.id);
    const lines = element.querySelector('ul[aria-label="Products in your order"]');
    expect(lines?.querySelectorAll('li')).toHaveLength(2);
    const keyboard = lines?.querySelector('li');
    expect(keyboard?.textContent).toContain('Purchased keyboard');
    expect(keyboard?.textContent).toContain('Quantity: 2');
    expect(keyboard?.textContent).toContain('Unit price: R$12.50');
    expect(lines?.textContent).toContain('Purchased mouse');
    expect(lines?.textContent).toContain('Quantity: 1');
    expect(lines?.textContent).toContain('Unit price: R$15.00');
    expect(element.querySelector('aside')?.textContent).toContain('R$42.75');
    expect(element.querySelector('a[href="/catalog"]')?.textContent).toContain('Continue shopping');
    expect(element.querySelector('input, p-inputnumber')).toBeNull();
  });

  it('presents PENDING as normal processing with stock guidance', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush(order);
    await settle(harness);
    expect(element.textContent).toContain('Processing');
    expect(element.textContent).toContain('We are confirming your stock reservation.');
    expect(element.querySelector('p-message[severity="error"]')).toBeNull();
  });

  it('shows another status without pending processing guidance', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush({ ...order, status: 'CONFIRMED' });
    await settle(harness);
    expect(element.textContent).toContain('Confirmed');
    expect(element.textContent).not.toContain('We are confirming your stock reservation.');
  });

  it.each([
    ['2026-03-26T14:30:00', 'Mar 26, 2026, 2:30 PM'],
    ['2026-01-01T00:05:00', 'Jan 1, 2026, 12:05 AM'],
    ['2026-03-08T02:30:00', 'Mar 8, 2026, 2:30 AM'],
    ['2026-12-31T23:59:59.123456', 'Dec 31, 2026, 11:59 PM'],
  ])('formats %s as local date and time without changing the time', async (orderTime, expected) => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush({ ...order, orderTime });
    await settle(harness);
    const time = element.querySelector('time');
    expect(time?.textContent?.trim()).toBe(expected);
    expect(time?.getAttribute('datetime')).toBe(orderTime);
  });

  it('shows order not found on 404 without exposing server detail', async () => {
    const { harness, element } = await render();
    http
      .expectOne('/api/order/' + order.id)
      .flush({ detail: 'Another customer order.' }, { status: 404, statusText: 'Not Found' });
    await settle(harness);
    expect(element.textContent).toContain('Order not found.');
    expect(element.textContent).not.toContain('Another customer order.');
    expect(element.textContent).not.toContain('Order confirmed!');
    expect(element.querySelector('button')).toBeNull();
    expect(element.querySelector('a[href="/catalog"]')?.textContent).toContain('Continue shopping');
  });

  it.each([
    'invalid-order',
    'd715a7c9-d942-4c65-92eb-704c797dc6eg',
    'd715a7c9d9424c6592eb704c797dc6ee',
  ])('rejects invalid ID %s without calling the API', async (id) => {
    const { harness, element } = await render('/orders/' + id + '/confirmation');
    await settle(harness);
    expect(element.textContent).toContain('Order not found.');
    expect(element.textContent).not.toContain('Order confirmed!');
    http.expectNone((request) => request.url.startsWith('/api/'));
  });

  it.each([
    [{ detail: 'Orders unavailable.' }, 'Orders unavailable.'],
    [{}, 'Unable to load the order. Please try again.'],
  ])('shows ProblemDetail or fallback and allows retrying', async (problem, message) => {
    const { harness, element } = await render();
    http
      .expectOne('/api/order/' + order.id)
      .flush(problem, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message[severity="error"]')?.textContent).toContain(message);
    expect(element.textContent).not.toContain('Order confirmed!');
    const retry = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Try again'),
    );
    expect(retry).toBeDefined();
    retry?.click();
    TestBed.tick();
    http.expectOne('/api/order/' + order.id).flush(order);
    await settle(harness);
    expect(element.querySelector('p-message[severity="error"]')).toBeNull();
    expect(element.querySelector('h1')?.textContent).toContain('Order confirmed!');
  });

  it('follows ID changes and removes previous data when receiving an invalid ID', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush(order);
    await settle(harness);
    const secondId = 'f715a7c9-d942-4c65-92eb-704c797dc6ee';
    await harness.navigateByUrl('/orders/' + secondId + '/confirmation');
    TestBed.tick();
    expect(element.textContent).not.toContain(order.id);
    http.expectOne('/api/order/' + secondId).flush({ ...order, id: secondId });
    await settle(harness);
    expect(element.textContent).toContain(secondId);
    await harness.navigateByUrl('/orders/invalid/confirmation');
    await settle(harness);
    expect(element.textContent).toContain('Order not found.');
    expect(element.textContent).not.toContain(secondId);
    http.expectNone((request) => request.url.startsWith('/api/'));
  });

  it('requires login on direct navigation without fetching the order', async () => {
    TestBed.inject(AuthService).clear();
    await RouterTestingHarness.create(url);
    expect(login).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
    http.expectNone((request) => request.url.startsWith('/api/'));
  });
});
