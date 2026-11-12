import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ApplicationRef, signal } from '@angular/core';
import { ProductApi } from './product-api';
import { Product } from './product';

describe('ProductApi', () => {
  let http: HttpTestingController;
  let api: ProductApi;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(ProductApi);
  });

  afterEach(() => http.verify());

  it('sends a zero-based page, eight items and filters', async () => {
    const result = TestBed.runInInjectionContext(() =>
      api.list(() => ({ page: 2, name: ' Keyboard ', category: ' Peripherals ' })),
    );
    TestBed.tick();
    const request = http.expectOne((req) => req.url === '/api/product');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('8');
    expect(request.request.params.get('name')).toBe('Keyboard');
    expect(request.request.params.get('category')).toBe('Peripherals');
    const response = { content: [], page: 2, size: 8, totalElements: 0, totalPages: 0 };
    request.flush(response);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()).toEqual(response);
  });

  it('allows requesting four products without changing the default size', async () => {
    const result = TestBed.runInInjectionContext(() =>
      api.list(() => ({ page: 0, name: '', category: '' }), 4),
    );
    TestBed.tick();
    const response = { content: [], page: 0, size: 4, totalElements: 0, totalPages: 0 };
    http.expectOne('/api/product?page=0&size=4').flush(response);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()).toEqual(response);
  });

  it('omits empty filters and repeats the request when the filter changes', () => {
    const page = signal(0);
    TestBed.runInInjectionContext(() =>
      api.list(() => ({ page: page(), name: '  ', category: '' })),
    );
    TestBed.tick();
    const request = http.expectOne('/api/product?page=0&size=8');
    expect(request.request.params.has('name')).toBe(false);
    expect(request.request.params.has('category')).toBe(false);
    request.flush({ content: [], page: 0, size: 8, totalElements: 0, totalPages: 0 });
    page.set(1);
    TestBed.tick();
    http.expectOne('/api/product?page=1&size=8').flush({
      content: [],
      page: 1,
      size: 8,
      totalElements: 0,
      totalPages: 0,
    });
  });

  it('fetches a product by ID with a relative URL and follows ID changes', async () => {
    const id = signal('670000000000000000000001');
    const result = TestBed.runInInjectionContext(() => api.byId(id));
    TestBed.tick();
    const request = http.expectOne('/api/product/' + id());
    expect(request.request.method).toBe('GET');
    const product: Product = {
      id: id(),
      name: 'TKL Mechanical Keyboard',
      description: 'Compact keyboard',
      price: 349.9,
      category: 'Peripherals',
      imageUrl: null,
      version: 0,
    };
    request.flush(product, { headers: { ETag: '"0"' } });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()).toEqual(product);
    id.set('another-id');
    TestBed.tick();
    http.expectOne('/api/product/another-id').flush({ ...product, id: 'another-id' });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()?.id).toBe('another-id');
  });

  it('provides composable reading by ID with an encoded URL', async () => {
    const result = firstValueFrom(api.getById('a/b &'));
    const request = http.expectOne('/api/product/a%2Fb%20%26');
    expect(request.request.method).toBe('GET');
    const product: Product = {
      id: 'a/b &',
      name: 'Keyboard',
      price: 12.5,
      description: '',
      category: '',
      imageUrl: null,
      version: 0,
    };
    request.flush(product);
    expect(await result).toEqual(product);
  });

  it('loads categories in an independent resource', async () => {
    const result = TestBed.runInInjectionContext(() => api.categories());
    TestBed.tick();
    http.expectOne('/api/product/categories').flush(['Accessories', 'Peripherals']);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()).toEqual(['Accessories', 'Peripherals']);
  });
});
