import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MessageService } from 'primeng/api';
import { AdminProductForm } from './admin-product-form';

@Component({ template: '<h1>Products</h1>' })
class ListStub {}

const product = {
  id: '1',
  name: 'Keyboard',
  description: 'Mechanical keyboard',
  category: 'Peripherals',
  price: 1234.56,
  imageUrl: 'https://example.com/image.png',
  version: 3,
};

describe('AdminProductForm', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let element: HTMLElement;
  let toast: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
        provideRouter([
          { path: 'admin/products/new', component: AdminProductForm },
          { path: 'admin/products/:id/edit', component: AdminProductForm },
          { path: 'admin/products', component: ListStub },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    toast = vi.spyOn(TestBed.inject(MessageService), 'add');
  });
  afterEach(() => http.verify());

  async function render(edit = false, complete = true, quantity: number | null = 8) {
    harness = await RouterTestingHarness.create(
      '/admin/products/' + (edit ? '1/edit' : 'new') + '?name=Keyboard&extra=1',
    );
    TestBed.tick();
    http.expectOne('/api/product/categories').flush(['Peripherals']);
    if (edit) {
      http
        .expectOne('/api/inventory?skuCode=1')
        .flush(quantity === null ? [] : [{ skuCode: '1', quantity, inStock: quantity > 0 }]);
    }
    if (edit && complete) http.expectOne('/api/product/1').flush(product);
    if (!edit || complete) await settle();
    else harness.detectChanges();
    const root = harness.routeNativeElement;
    if (!root) throw new Error('Form missing');
    element = root;
  }

  async function settle() {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function field(label: string): HTMLInputElement | HTMLTextAreaElement {
    const control = element.querySelector<HTMLInputElement | HTMLTextAreaElement>('#' + label);
    if (!control) throw new Error('Field missing: ' + label);
    return control;
  }

  function fill(
    name = ' Keyboard ',
    description = ' Mechanical keyboard ',
    category = ' New category ',
    price = '1,234.56',
    quantity = '8',
  ) {
    for (const [key, value] of Object.entries({ name, description, category, price, quantity })) {
      const input = field(key);
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    harness.detectChanges();
  }

  function submit() {
    element
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));
    harness.detectChanges();
  }

  function fillImageUrl(value: string) {
    const input = field('imageUrl');
    input.value = value;
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();
  }

  function click(text: string) {
    const button = Array.from(element.querySelectorAll('button')).find(
      (item) => item.textContent?.trim() === text,
    );
    if (!button) throw new Error('Action missing: ' + text);
    button.click();
  }

  function stockSaved(method: 'POST' | 'PUT' = 'POST', quantity = 8) {
    const request = http.expectOne(method === 'POST' ? '/api/inventory' : '/api/inventory/1');
    expect(request.request.method).toBe(method);
    expect(request.request.body).toEqual(
      method === 'POST' ? { skuCode: '1', quantity } : { quantity },
    );
    request.flush({ skuCode: '1', quantity, inStock: quantity > 0 });
  }

  it('creates with trimmed fields, numeric price and null image and preserves filters', async () => {
    await render();
    expect(element.querySelector('datalist option')?.getAttribute('value')).toBe('Peripherals');
    fill();
    submit();
    const req = http.expectOne('/api/product');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      name: 'Keyboard',
      description: 'Mechanical keyboard',
      category: 'New category',
      price: 1234.56,
      imageUrl: null,
    });
    http.expectNone((req) => req.url.startsWith('/api/inventory'));
    req.flush(product, { status: 201, statusText: 'Created' });
    expect(toast).not.toHaveBeenCalled();
    stockSaved();
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products?name=Keyboard&extra=1');
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success' }));
  });

  it('creates with a trimmed image URL', async () => {
    await render();
    fill();
    fillImageUrl(' https://example.com/new.png ');
    submit();
    const req = http.expectOne('/api/product');
    expect(req.request.body.imageUrl).toBe('https://example.com/new.png');
    req.flush(product);
    stockSaved();
    await settle();
  });

  it('sends a null image when the URL contains only spaces', async () => {
    await render();
    fill();
    fillImageUrl('   ');
    submit();
    const req = http.expectOne('/api/product');
    expect(req.request.body.imageUrl).toBeNull();
    req.flush(product);
    stockSaved();
    await settle();
  });

  it('loads editing, formats the price and sends the updated image and ETag', async () => {
    await render(true);
    expect(field('price').value).toBe('1,234.56');
    expect(field('name').value).toBe('Keyboard');
    expect(field('quantity').value).toBe('8');
    expect(field('imageUrl').value).toBe(product.imageUrl);
    fillImageUrl(' https://example.com/changed.png ');
    submit();
    const req = http.expectOne('/api/product/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body.imageUrl).toBe('https://example.com/changed.png');
    req.flush({ ...product, version: 4 });
    stockSaved('PUT');
    await settle();
  });

  it('fills a null image with an empty field', async () => {
    await render(true, false);
    http.expectOne('/api/product/1').flush({ ...product, imageUrl: null });
    await settle();
    expect(field('imageUrl').value).toBe('');
    expect(element.querySelector('img')).toBeNull();
  });

  it('allows removing the existing image', async () => {
    await render(true);
    fillImageUrl('');
    submit();
    const req = http.expectOne('/api/product/1');
    expect(req.request.body.imageUrl).toBeNull();
    req.flush({ ...product, imageUrl: null, version: 4 });
    stockSaved('PUT');
    await settle();
  });

  it.each([
    ['http://example.com/image.png', 'Enter a valid HTTPS URL'],
    ['https://example.com/' + 'a'.repeat(2029), 'The URL must be at most 2048 characters'],
  ])('blocks an invalid URL and shows an accessible error: %s', async (url, message) => {
    await render();
    fill();
    fillImageUrl(url);
    submit();
    http.expectNone((req) => req.method !== 'GET');
    expect(field('imageUrl').getAttribute('aria-invalid')).toBe('true');
    expect(field('imageUrl').getAttribute('aria-describedby')).toBe('imageUrl-error');
    expect(element.querySelector('#imageUrl-error')?.textContent).toContain(message);
    expect(element.querySelector('img')).toBeNull();
  });

  it('shows a valid preview and a warning without blocking submission when the image fails', async () => {
    await render();
    fill();
    expect(element.querySelector('img')).toBeNull();
    fillImageUrl(' https://example.com/image.png ');
    const preview = element.querySelector('img');
    if (!preview) throw new Error('Preview missing');
    expect(preview.getAttribute('src')).toBe(product.imageUrl);
    expect(preview.getAttribute('alt')).toBe('Product image preview');
    expect(preview.getAttribute('loading')).toBe('lazy');
    expect(preview.getAttribute('referrerpolicy')).toBe('no-referrer');
    preview.dispatchEvent(new Event('error'));
    harness.detectChanges();
    expect(element.querySelector('img')).toBeNull();
    expect(element.textContent).toContain('Unable to load the image');
    expect(field('imageUrl').getAttribute('aria-invalid')).toBe('false');
    expect(field('imageUrl').getAttribute('aria-describedby')).toBe('imageUrl-hint');
    submit();
    const req = http.expectOne('/api/product');
    expect(req.request.body.imageUrl).toBe(product.imageUrl);
    req.flush(product);
    stockSaved();
    await settle();
  });

  it('tries a new preview after changing the failed URL', async () => {
    await render();
    fillImageUrl(product.imageUrl);
    element.querySelector('img')?.dispatchEvent(new Event('error'));
    harness.detectChanges();
    fillImageUrl('https://example.com/other.png');
    expect(element.querySelector('img')?.getAttribute('src')).toBe('https://example.com/other.png');
    expect(element.textContent).not.toContain('Unable to load the image');
    fillImageUrl('');
    expect(element.querySelector('img')).toBeNull();
  });

  it('does not submit an invalid form and connects accessible messages to fields', async () => {
    await render();
    submit();
    http.expectNone((req) => req.method === 'POST');
    expect(field('name').getAttribute('aria-invalid')).toBe('true');
    expect(field('name').getAttribute('aria-describedby')).toBe('name-error');
    expect(element.querySelector('#name-error')?.textContent).toContain('name');
    fill('a'.repeat(256), 'short', 'a'.repeat(101), '1234,56');
    submit();
    expect(element.textContent).toContain('1,234.56');
    http.expectNone((req) => req.method === 'POST');
  });

  it.each([
    [' ', 'Valid description', 'Category', '1'],
    ['Name', ' '.repeat(10), 'Category', '1'],
    ['Name', 'a'.repeat(2001), 'Category', '1'],
    ['Name', 'Valid description', ' ', '1'],
    ['Name', 'Valid description', 'Category', '1000000000000'],
  ])(
    'validates normalized rules before submission: %s',
    async (name, description, category, price) => {
      await render();
      fill(name, description, category, price);
      submit();
      http.expectNone((req) => req.method === 'POST');
    },
  );

  it('prevents duplicate submission while saving and shows the busy state', async () => {
    await render();
    fill();
    submit();
    submit();
    const requests = http.match((req) => req.method === 'POST');
    expect(requests).toHaveLength(1);
    expect(element.querySelector('form')?.getAttribute('aria-busy')).toBe('true');
    expect(element.textContent).toContain('SAVING');
    requests[0].flush(product);
    submit();
    http.expectNone('/api/product');
    stockSaved();
    await settle();
  });

  it('maps 400 fields and shows detail when a field is unknown', async () => {
    await render();
    fill();
    submit();
    http.expectOne('/api/product').flush(
      {
        detail: 'Review the submitted details.',
        fields: { name: 'Name already in use.', unknownField: 'Invalid field.' },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(element.querySelector('#name-error')?.textContent).toContain('Name already in use.');
    expect(element.textContent).toContain('Review the submitted details.');
    field('name').value = 'Another name';
    field('name').dispatchEvent(new Event('input'));
    harness.detectChanges();
    expect(element.querySelector('#name-error')).toBeNull();
  });

  it('preserves input on 412 and replaces values and version only after an explicit reload', async () => {
    await render(true);
    fill('My name');
    submit();
    http
      .expectOne('/api/product/1')
      .flush({ detail: 'Conflict' }, { status: 412, statusText: 'Precondition Failed' });
    await settle();
    expect(field('name').value).toBe('My name');
    expect(element.textContent).toContain('Someone else changed');
    http.expectNone('/api/product/1');
    click('Reload details');
    TestBed.tick();
    http.expectOne('/api/product/1').flush({
      ...product,
      name: 'Updated',
      version: 8,
      imageUrl: 'https://example.com/new.png',
    });
    http
      .expectOne('/api/inventory?skuCode=1')
      .flush([{ skuCode: '1', quantity: 21, inStock: true }]);
    await settle();
    expect(field('name').value).toBe('Updated');
    expect(field('quantity').value).toBe('21');
    submit();
    const req = http.expectOne('/api/product/1');
    expect(req.request.headers.get('If-Match')).toBe('"8"');
    expect(req.request.body.imageUrl).toBe('https://example.com/new.png');
    req.flush({ ...product, version: 9 });
    stockSaved('PUT', 21);
    await settle();
  });

  it('cancels without writing and preserves query parameters', async () => {
    await render();
    click('CANCEL');
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products?name=Keyboard&extra=1');
    http.expectNone((req) => req.method !== 'GET');
  });

  it('shows skeletons until editing data finishes loading', async () => {
    await render(true, false);
    expect(element.querySelector('p-skeleton')).not.toBeNull();
    expect(element.querySelector('form')).toBeNull();
    http.expectOne('/api/product/1').flush(product);
    await settle();
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('form')).not.toBeNull();
  });

  it('shows a missing product and a back link on 404', async () => {
    await render(true, false);
    http
      .expectOne('/api/product/1')
      .flush({ detail: 'Missing' }, { status: 404, statusText: 'Not Found' });
    await settle();
    expect(element.textContent).toContain('Product not found');
    expect(element.querySelector('a')?.getAttribute('href')).toBe(
      '/admin/products?name=Keyboard&extra=1',
    );
    expect(element.querySelector('form')).toBeNull();
  });

  it('shows detail and allows retrying a failed load', async () => {
    await render(true, false);
    http
      .expectOne('/api/product/1')
      .flush({ detail: 'Temporary failure.' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(element.textContent).toContain('Temporary failure.');
    click('Try again');
    TestBed.tick();
    http.expectOne('/api/product/1').flush(product);
    await settle();
    expect(field('name').value).toBe('Keyboard');
  });

  it('shows a save error and allows a manual retry', async () => {
    await render();
    fill();
    submit();
    http
      .expectOne('/api/product')
      .flush({ detail: 'Service unavailable.' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(element.textContent).toContain('Service unavailable.');
    expect(field('name').value).toBe(' Keyboard ');
    submit();
    http.expectOne('/api/product').flush(product);
    stockSaved();
    await settle();
  });
  it('maps all known 400 fields without a redundant global error', async () => {
    await render();
    fill();
    submit();
    http.expectOne('/api/product').flush(
      {
        detail: 'Field validation.',
        fields: {
          name: 'Invalid name.',
          category: 'Invalid category.',
          price: 'Invalid price.',
          description: 'Invalid description.',
          imageUrl: 'Invalid image.',
        },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    for (const [id, message] of Object.entries({
      name: 'Invalid name.',
      category: 'Invalid category.',
      price: 'Invalid price.',
      description: 'Invalid description.',
      imageUrl: 'Invalid image.',
    })) {
      expect(element.querySelector('#' + id + '-error')?.textContent).toContain(message);
      expect(field(id).getAttribute('aria-invalid')).toBe('true');
    }
    expect(element.textContent).not.toContain('Field validation.');
  });

  it('cancels editing without PUT and preserves filters', async () => {
    await render(true);
    fill();
    click('CANCEL');
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products?name=Keyboard&extra=1');
    http.expectNone((req) => req.method !== 'GET');
  });

  it('allows creating a category when the category query fails', async () => {
    harness = await RouterTestingHarness.create('/admin/products/new');
    TestBed.tick();
    http.expectOne('/api/product/categories').flush({}, { status: 503, statusText: 'Unavailable' });
    await settle();
    const root = harness.routeNativeElement;
    if (!root) throw new Error('Form missing');
    element = root;
    fill();
    expect(field('category').disabled).toBe(false);
    submit();
    http.expectOne('/api/product').flush(product);
    stockSaved();
    await settle();
  });
  it.each(['', '-1', '1.5', '2147483648'])(
    'rejects invalid stock %s with an accessible error',
    async (quantity) => {
      await render();
      fill(undefined, undefined, undefined, undefined, quantity);
      submit();
      http.expectNone((req) => req.method !== 'GET');
      expect(field('quantity').getAttribute('aria-invalid')).toBe('true');
      expect(field('quantity').getAttribute('aria-describedby')).toBe('quantity-error');
      expect(element.querySelector('#quantity-error')?.textContent).toContain('whole');
    },
  );

  it.each([0, 2147483647])('accepts the stock limit %s', async (quantity) => {
    await render();
    fill(undefined, undefined, undefined, undefined, String(quantity));
    submit();
    http.expectOne('/api/product').flush(product);
    stockSaved('POST', quantity);
    await settle();
  });

  it('prefills zero without a record and creates stock when saving an edit', async () => {
    await render(true, true, null);
    expect(field('quantity').value).toBe('0');
    submit();
    http.expectOne('/api/product/1').flush({ ...product, version: 4 });
    stockSaved('POST', 0);
    await settle();
  });

  it('creates stock when PUT finds a removed record', async () => {
    await render(true);
    submit();
    http.expectOne('/api/product/1').flush({ ...product, version: 4 });
    http.expectOne('/api/inventory/1').flush({}, { status: 404, statusText: 'Not Found' });
    stockSaved();
    await settle();
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success' }));
  });

  it.each([400, 409, 503])(
    'preserves the created product and allows retrying after stock error %s',
    async (status) => {
      await render();
      fill();
      submit();
      http.expectOne('/api/product').flush({ ...product, version: 4 });
      http
        .expectOne('/api/inventory')
        .flush(
          { detail: 'Stock update failed.', fields: { quantity: 'Quantity rejected.' } },
          { status, statusText: 'Error' },
        );
      await settle();
      expect(element.textContent).toContain('The product was saved, but stock was not');
      expect(element.textContent).toContain('Stock update failed.');
      expect(element.textContent).toContain('Try saving again');
      expect(field('quantity').value).toBe('8');
      expect(toast).not.toHaveBeenCalled();
      expect(TestBed.inject(Router).url).toContain('/admin/products/new');
      fill();
      submit();
      http.expectNone('/api/product');
      const update = http.expectOne('/api/product/1');
      expect(update.request.method).toBe('PUT');
      expect(update.request.headers.get('If-Match')).toBe('"4"');
      update.flush({ ...product, version: 5 });
      stockSaved('PUT');
      await settle();
      expect(TestBed.inject(Router).url).toBe('/admin/products?name=Keyboard&extra=1');
    },
  );

  it('does not POST after a non-404 PUT failure and preserves the new version', async () => {
    await render(true);
    submit();
    http.expectOne('/api/product/1').flush({ ...product, version: 4 });
    http.expectOne('/api/inventory/1').flush({}, { status: 503, statusText: 'Unavailable' });
    await settle();
    http.expectNone('/api/inventory');
    expect(element.textContent).toContain('The product was saved, but stock was not');
    expect(toast).not.toHaveBeenCalled();
    submit();
    const update = http.expectOne('/api/product/1');
    expect(update.request.headers.get('If-Match')).toBe('"4"');
    update.flush({ ...product, version: 5 });
    http.expectOne('/api/inventory/1').flush({}, { status: 404, statusText: 'Not Found' });
    stockSaved();
    await settle();
  });

  it('waits for stock and allows retrying a failed query without assuming zero', async () => {
    harness = await RouterTestingHarness.create('/admin/products/1/edit');
    TestBed.tick();
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product/1').flush(product);
    await Promise.resolve();
    harness.detectChanges();
    const root = harness.routeNativeElement;
    if (!root) throw new Error('Form missing');
    element = root;
    expect(element.querySelector('form')).toBeNull();
    http
      .expectOne('/api/inventory?skuCode=1')
      .flush({ detail: 'Stock unavailable.' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(element.textContent).toContain('Stock unavailable.');
    expect(element.querySelector('form')).toBeNull();
    click('Try again');
    TestBed.tick();
    http
      .expectOne('/api/inventory?skuCode=1')
      .flush([{ skuCode: '1', quantity: 12, inStock: true }]);
    await settle();
    expect(field('quantity').value).toBe('12');
  });
  it('allows reloading a conflict after creating a product with a stock failure', async () => {
    await render();
    fill();
    submit();
    http.expectOne('/api/product').flush({ ...product, version: 4 });
    http.expectOne('/api/inventory').flush({}, { status: 503, statusText: 'Unavailable' });
    await settle();
    submit();
    http.expectOne('/api/product/1').flush({}, { status: 412, statusText: 'Precondition Failed' });
    await settle();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    click('Reload details');
    await navigate.mock.results[0]?.value;
    TestBed.tick();
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product/1').flush({ ...product, version: 8 });
    http.expectOne('/api/inventory?skuCode=1').flush([]);
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products/1/edit?name=Keyboard&extra=1');
    const root = harness.routeNativeElement;
    if (!root) throw new Error('Form missing');
    element = root;
    submit();
    const request = http.expectOne('/api/product/1');
    expect(request.request.headers.get('If-Match')).toBe('"8"');
    request.flush({ ...product, version: 9 });
    stockSaved('POST', 0);
    await settle();
  });
});
