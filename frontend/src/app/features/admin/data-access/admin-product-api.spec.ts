import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminProductApi, AdminProductRequest } from './admin-product-api';

describe('AdminProductApi', () => {
  let http: HttpTestingController;
  let api: AdminProductApi;
  const body: AdminProductRequest = {
    name: 'Keyboard',
    description: 'Mechanical keyboard',
    price: 1234.56,
    category: 'Peripherals',
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

  it('creates via POST with a null image and returns the product', () => {
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

  it('updates via PUT with a quoted strong ETag and preserves the body', () => {
    const request = { ...body, imageUrl: 'https://example.com/image.png' };
    api.update('a/b', 3, request).subscribe();
    const req = http.expectOne('/api/product/a%2Fb');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual(request);
    req.flush({ ...request, id: 'a/b', version: 4 });
  });

  it('deletes via DELETE with a strong ETag and no body', () => {
    api.remove('1', 0).subscribe();
    const req = http.expectOne('/api/product/1');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.headers.get('If-Match')).toBe('"0"');
    expect(req.request.body).toBeNull();
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
