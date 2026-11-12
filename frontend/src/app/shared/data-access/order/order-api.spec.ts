import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { OrderApi } from './order-api';

const order = {
  id: 'order-1',
  status: 'PENDING',
  totalValue: 25,
  orderTime: '2026-11-08T10:30:00',
  items: [{ productId: 'a', productName: 'Keyboard', quantity: 2, priceAtPurchase: 12.5 }],
};

describe('OrderApi', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates an order with items and an idempotency key and returns the 201 body', () => {
    const result = vi.fn();
    TestBed.inject(OrderApi)
      .create({ items: [{ productId: 'a', quantity: 2 }] }, 'chave-1')
      .subscribe(result);
    const request = http.expectOne('/api/order');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ items: [{ productId: 'a', quantity: 2 }] });
    expect(request.request.headers.get('Idempotency-Key')).toBe('chave-1');
    request.flush(order, { status: 201, statusText: 'Created' });
    expect(result).toHaveBeenCalledExactlyOnceWith(order);
  });

  it.each([200, 202, 204])('does not treat HTTP %s as a created order', (status) => {
    const result = vi.fn();
    const error = vi.fn();
    TestBed.inject(OrderApi).create({ items: [] }, 'chave-1').subscribe({ next: result, error });
    http.expectOne('/api/order').flush(status === 204 ? null : order, { status, statusText: 'OK' });
    expect(result).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledOnce();
  });

  it('propagates ProblemDetail to the consumer', () => {
    const error = vi.fn();
    TestBed.inject(OrderApi).create({ items: [] }, 'chave-1').subscribe({ error });
    http
      .expectOne('/api/order')
      .flush({ detail: 'Insufficient stock.' }, { status: 409, statusText: 'Conflict' });
    expect(error).toHaveBeenCalledWith(
      expect.objectContaining({ status: 409, error: { detail: 'Insufficient stock.' } }),
    );
  });

  it('fetches an order by encoded ID and follows ID changes', async () => {
    const id = signal('a/b &');
    const resource = TestBed.runInInjectionContext(() => TestBed.inject(OrderApi).byId(id));
    TestBed.tick();
    const request = http.expectOne('/api/order/a%2Fb%20%26');
    expect(request.request.method).toBe('GET');
    request.flush(order);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(resource.value()).toEqual(order);
    id.set('order-2');
    TestBed.tick();
    http.expectOne('/api/order/order-2').flush({ ...order, id: 'order-2' });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(resource.value()?.id).toBe('order-2');
  });

  it('does not fetch while the ID is empty and discards the order when the ID is cleared', async () => {
    const id = signal('');
    const resource = TestBed.runInInjectionContext(() => TestBed.inject(OrderApi).byId(id));
    TestBed.tick();
    http.expectNone((request) => request.url.startsWith('/api/order'));
    id.set('order-1');
    TestBed.tick();
    http.expectOne('/api/order/order-1').flush(order);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(resource.value()).toEqual(order);
    id.set('');
    TestBed.tick();
    await TestBed.inject(ApplicationRef).whenStable();
    http.expectNone((request) => request.url.startsWith('/api/order'));
    expect(resource.hasValue()).toBe(false);
  });
});
