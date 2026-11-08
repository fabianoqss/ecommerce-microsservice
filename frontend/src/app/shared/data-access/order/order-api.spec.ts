import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { OrderApi } from './order-api';

const order = {
  id: 'pedido-1',
  status: 'PENDING',
  totalValue: 25,
  orderTime: '2026-11-08T10:30:00',
  items: [{ productId: 'a', productName: 'Teclado', quantity: 2, priceAtPurchase: 12.5 }],
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

  it('cria pedido com itens e chave de idempotência e retorna o corpo do 201', () => {
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

  it.each([200, 202, 204])('não considera HTTP %s como pedido criado', (status) => {
    const result = vi.fn();
    const error = vi.fn();
    TestBed.inject(OrderApi).create({ items: [] }, 'chave-1').subscribe({ next: result, error });
    http.expectOne('/api/order').flush(status === 204 ? null : order, { status, statusText: 'OK' });
    expect(result).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledOnce();
  });

  it('propaga o ProblemDetail para o consumidor', () => {
    const error = vi.fn();
    TestBed.inject(OrderApi).create({ items: [] }, 'chave-1').subscribe({ error });
    http
      .expectOne('/api/order')
      .flush({ detail: 'Estoque insuficiente.' }, { status: 409, statusText: 'Conflict' });
    expect(error).toHaveBeenCalledWith(
      expect.objectContaining({ status: 409, error: { detail: 'Estoque insuficiente.' } }),
    );
  });

  it('busca pedido pelo id codificado e acompanha mudanças do id', async () => {
    const id = signal('a/b &');
    const resource = TestBed.runInInjectionContext(() => TestBed.inject(OrderApi).byId(id));
    TestBed.tick();
    const request = http.expectOne('/api/order/a%2Fb%20%26');
    expect(request.request.method).toBe('GET');
    request.flush(order);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(resource.value()).toEqual(order);
    id.set('pedido-2');
    TestBed.tick();
    http.expectOne('/api/order/pedido-2').flush({ ...order, id: 'pedido-2' });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(resource.value()?.id).toBe('pedido-2');
  });
});
