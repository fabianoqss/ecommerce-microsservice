import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminProductApi, AdminProductRequest } from './admin-product-api';

describe('AdminProductApi', () => {
  let http: HttpTestingController;
  let api: AdminProductApi;
  const body: AdminProductRequest = {
    name: 'Teclado',
    description: 'Teclado mecânico',
    price: 1234.56,
    category: 'Periféricos',
    imageUrl: null,
  };
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(AdminProductApi);
  });
  afterEach(() => http.verify());

  it('cria por POST com imagem nula e devolve o produto', () => {
    const next = vi.fn();
    api.create(body).subscribe(next);
    const req = http.expectOne('/api/product');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    expect(req.request.headers.has('If-Match')).toBe(false);
    const product = { ...body, id: '1', version: 0 };
    req.flush(product, { status: 201, statusText: 'Created' });
    expect(next).toHaveBeenCalledWith(product);
  });

  it('atualiza por PUT com ETag forte entre aspas e preserva o corpo', () => {
    const request = { ...body, imageUrl: 'https://example.com/image.png' };
    api.update('a/b', 3, request).subscribe();
    const req = http.expectOne('/api/product/a%2Fb');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual(request);
    req.flush({ ...request, id: 'a/b', version: 4 });
  });

  it('exclui por DELETE com ETag forte e sem corpo', () => {
    api.remove('1', 0).subscribe();
    const req = http.expectOne('/api/product/1');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.headers.get('If-Match')).toBe('"0"');
    expect(req.request.body).toBeNull();
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
