import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
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

  it('envia página zero-based, oito itens e os filtros', async () => {
    const result = TestBed.runInInjectionContext(() =>
      api.list(() => ({ page: 2, name: ' Teclado ', category: ' Periféricos ' })),
    );
    TestBed.tick();
    const request = http.expectOne((req) => req.url === '/api/product');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('8');
    expect(request.request.params.get('name')).toBe('Teclado');
    expect(request.request.params.get('category')).toBe('Periféricos');
    const response = { content: [], page: 2, size: 8, totalElements: 0, totalPages: 0 };
    request.flush(response);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()).toEqual(response);
  });

  it('omite filtros vazios e refaz a requisição quando o filtro muda', () => {
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

  it('busca um produto pelo id com URL relativa e acompanha mudanças do id', async () => {
    const id = signal('670000000000000000000001');
    const result = TestBed.runInInjectionContext(() => api.byId(id));
    TestBed.tick();
    const request = http.expectOne('/api/product/' + id());
    expect(request.request.method).toBe('GET');
    const product: Product = {
      id: id(),
      name: 'Teclado Mecânico TKL',
      description: 'Teclado compacto',
      price: 349.9,
      category: 'Periféricos',
      imageUrl: null,
      version: 0,
    };
    request.flush(product, { headers: { ETag: '"0"' } });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()).toEqual(product);
    id.set('outro-id');
    TestBed.tick();
    http.expectOne('/api/product/outro-id').flush({ ...product, id: 'outro-id' });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()?.id).toBe('outro-id');
  });

  it('carrega categorias em um recurso independente', async () => {
    const result = TestBed.runInInjectionContext(() => api.categories());
    TestBed.tick();
    http.expectOne('/api/product/categories').flush(['Acessórios', 'Periféricos']);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(result.value()).toEqual(['Acessórios', 'Periféricos']);
  });
});
