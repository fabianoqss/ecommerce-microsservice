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

  it('consulta somente os SKUs solicitados com parâmetros repetidos e codificados', () => {
    const result = vi.fn();
    TestBed.inject(InventoryApi).getBySkus(['a', 'b/c &']).subscribe(result);
    const request = http.expectOne((request) => request.url === '/api/inventory');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.getAll('skuCode')).toEqual(['a', 'b/c &']);
    const response = [{ skuCode: 'a', quantity: 4, inStock: true }];
    request.flush(response);
    expect(result).toHaveBeenCalledExactlyOnceWith(response);
  });

  it('busca estoque usando parâmetros repetidos e acompanha mudanças dos SKUs', async () => {
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

  it('não solicita estoque para lista vazia', () => {
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

  it('não repete a consulta quando a lista muda de referência com os mesmos SKUs', () => {
    const skus = signal(['a', 'b']);
    TestBed.runInInjectionContext(() => TestBed.inject(InventoryApi).bySkus(skus));
    TestBed.tick();
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([]);
    skus.set(['a', 'b']);
    TestBed.tick();
    http.expectNone(() => true);
  });
});
