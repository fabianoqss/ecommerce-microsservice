import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { InventoryApi } from './inventory-api';

describe('InventoryApi', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('creates stock with SKU and quantity using a relative URL', () => {
    const result = vi.fn();
    TestBed.inject(InventoryApi).create({ skuCode: 'product-1', quantity: 0 }).subscribe(result);
    const request = http.expectOne('/api/inventory');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ skuCode: 'product-1', quantity: 0 });
    const response = { skuCode: 'product-1', quantity: 0, inStock: false };
    request.flush(response, { status: 201, statusText: 'Created' });
    expect(result).toHaveBeenCalledExactlyOnceWith(response);
  });

  it('updates stock with an encoded SKU and sends only quantity', () => {
    const result = vi.fn();
    TestBed.inject(InventoryApi).update('a/b &', { quantity: 2147483647 }).subscribe(result);
    const request = http.expectOne('/api/inventory/a%2Fb%20%26');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ quantity: 2147483647 });
    const response = { skuCode: 'a/b &', quantity: 2147483647, inStock: true };
    request.flush(response);
    expect(result).toHaveBeenCalledExactlyOnceWith(response);
  });

  it.each([400, 404, 409, 503])('propagates error %s without repeating the write', (status) => {
    const error = vi.fn();
    TestBed.inject(InventoryApi).update('product-1', { quantity: 2 }).subscribe({ error });
    http
      .expectOne('/api/inventory/product-1')
      .flush({ detail: 'Stock update failed.' }, { status, statusText: 'Error' });
    expect(error).toHaveBeenCalledWith(expect.objectContaining({ status }));
    http.expectNone(() => true);
  });

  it('queries only requested SKUs with repeated encoded parameters', () => {
    const result = vi.fn();
    TestBed.inject(InventoryApi).getBySkus(['a', 'b/c &']).subscribe(result);
    const request = http.expectOne((request) => request.url === '/api/inventory');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.getAll('skuCode')).toEqual(['a', 'b/c &']);
    const response = [{ skuCode: 'a', quantity: 4, inStock: true }];
    request.flush(response);
    expect(result).toHaveBeenCalledExactlyOnceWith(response);
  });

  it('fetches stock using repeated parameters and follows SKU changes', async () => {
    const skus = signal(['a', 'b']);
    const resource = TestBed.runInInjectionContext(() => TestBed.inject(InventoryApi).bySkus(skus));
    TestBed.tick();
    const request = http.expectOne('/api/inventory?skuCode=a&skuCode=b');
    expect(request.request.method).toBe('GET');
    const response = [
      { skuCode: 'a', quantity: 2, inStock: true },
      { skuCode: 'b', quantity: 0, inStock: false },
    ];
    request.flush(response);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(resource.value()).toEqual(response);
    skus.set(['a/b &']);
    TestBed.tick();
    const changed = http.expectOne((request) => request.url === '/api/inventory');
    expect(changed.request.params.getAll('skuCode')).toEqual(['a/b &']);
    changed.flush([]);
  });

  it('does not request stock for an empty list', () => {
    const skus = signal<string[]>([]);
    TestBed.runInInjectionContext(() => TestBed.inject(InventoryApi).bySkus(skus));
    TestBed.tick();
    http.expectNone(() => true);
    skus.set(['a']);
    TestBed.tick();
    http.expectOne('/api/inventory?skuCode=a').flush([]);
    skus.set([]);
    TestBed.tick();
    http.expectNone(() => true);
  });

  it('does not repeat the query when the list reference changes with the same SKUs', () => {
    const skus = signal(['a', 'b']);
    TestBed.runInInjectionContext(() => TestBed.inject(InventoryApi).bySkus(skus));
    TestBed.tick();
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([]);
    skus.set(['a', 'b']);
    TestBed.tick();
    http.expectNone(() => true);
  });
});
