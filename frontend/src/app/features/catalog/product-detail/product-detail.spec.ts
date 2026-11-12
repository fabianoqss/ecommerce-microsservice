import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { NavigationEnd, provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, firstValueFrom } from 'rxjs';
import { catalogRoutes } from '../catalog.routes';
import { MessageService } from 'primeng/api';
import { AuthService } from '../../../core/auth/auth.service';
import { AUTH_LOCATION } from '../../../core/auth/auth-location';
import { Cart } from '../../../core/cart/cart';
import { CartStore } from '../../../core/cart/cart-store';
import { LocalStorageCartStore } from '../../../core/cart/local-storage-cart-store';
import { Product } from '../../../shared/data-access/product/product';

describe('ProductDetail', () => {
  let http: HttpTestingController;
  let login: ReturnType<typeof vi.fn>;
  const product: Product = {
    id: '670000000000000000000001',
    name: 'TKL Mechanical Keyboard',
    description: 'Compact keyboard.\nWith backlit keys.',
    price: 349.9,
    category: 'Peripherals',
    imageUrl: '/images/keyboard.png',
    version: 0,
  };
  const url = '/catalog/' + product.id;

  beforeEach(() => {
    localStorage.clear();
    login = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        { provide: CartStore, useClass: LocalStorageCartStore },
        { provide: AUTH_LOCATION, useValue: { assign: login } },
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [{ path: 'catalog', title: 'Catalog | DS Catalog', children: catalogRoutes }],
          withComponentInputBinding(),
        ),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  async function render(path = url, loadInventory = true) {
    const harness = await RouterTestingHarness.create(path);
    TestBed.tick();
    if (loadInventory) {
      for (const request of http.match((request) => request.url === '/api/inventory')) {
        request.flush(
          request.request.params
            .getAll('skuCode')
            ?.map((skuCode) => ({ skuCode, quantity: 10, inStock: true })) ?? [],
        );
      }
    }
    const element = harness.routeNativeElement;
    if (!element) {
      throw new Error('The product route was not rendered');
    }
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    TestBed.tick();
    for (const request of http.match((request) => request.url === '/api/inventory')) {
      request.flush(
        request.request.params
          .getAll('skuCode')
          ?.map((skuCode) => ({ skuCode, quantity: 10, inStock: true })) ?? [],
      );
    }
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  it('shows image, name, price and description skeletons while loading', async () => {
    const { harness, element } = await render();
    expect(element.querySelectorAll('p-skeleton')).toHaveLength(4);
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    expect(element.querySelector('[role="status"]')?.textContent).toContain('Loading product.');
    expect(TestBed.inject(Title).getTitle()).toBe('Product | DS Catalog');
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('false');
  });

  it('shows name, price, plain text description and accessible image and updates the title', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    expect(element.querySelector('h1')?.textContent?.trim()).toBe(product.name);
    expect(element.querySelector('[data-testid="price-integer"]')?.textContent).toBe('349');
    expect(element.querySelector('[data-testid="price-decimal"]')?.textContent).toBe('.90');
    expect(element.querySelector('h2')?.textContent?.trim()).toBe('Product description');
    expect(element.querySelector('.whitespace-pre-line')?.textContent?.trim()).toBe(
      product.description,
    );
    const image = element.querySelector('img');
    expect(image?.alt).toBe(product.name);
    expect(image?.getAttribute('src')).toBe(product.imageUrl);
    expect(image?.width).toBe(353);
    expect(image?.height).toBe(353);
    expect(TestBed.inject(Title).getTitle()).toBe(product.name + ' | DS Catalog');
  });

  it('shows product not found on 404 and offers a return to the catalog', async () => {
    const { harness, element } = await render('/catalog/invalid-id');
    http
      .expectOne('/api/product/invalid-id')
      .flush({ status: 404, title: 'Not found' }, { status: 404, statusText: 'Not Found' });
    await settle(harness);
    expect(element.textContent).toContain('Product not found.');
    const back = Array.from(element.querySelectorAll('a')).find((link) =>
      link.textContent?.includes('Back to catalog'),
    );
    expect(back?.getAttribute('href')).toBe('/catalog');
    expect(element.querySelector('p-message')).toBeNull();
    expect(TestBed.inject(Title).getTitle()).toBe('Product | DS Catalog');
  });

  it.each([
    [{ detail: 'Service unavailable.' }, 'Service unavailable.'],
    [{}, 'Unable to load the product. Please try again.'],
    [{ detail: '  ' }, 'Unable to load the product. Please try again.'],
  ])('shows an error and allows retrying', async (problem, message) => {
    const { harness, element } = await render();
    http
      .expectOne('/api/product/' + product.id)
      .flush(problem, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.getAttribute('severity')).toBe('error');
    expect(element.querySelector('p-message')?.textContent).toContain(message);
    element.querySelector<HTMLButtonElement>('button')?.click();
    TestBed.tick();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    expect(element.querySelector('p-message')).toBeNull();
    expect(element.querySelector('h1')?.textContent).toContain(product.name);
  });

  it('shows a placeholder when there is no image', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush({ ...product, imageUrl: null });
    await settle(harness);
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('.pi-image')).not.toBeNull();
  });

  it('shows a message when there is no description and does not interpret HTML', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush({ ...product, description: '' });
    await settle(harness);
    expect(element.textContent).toContain('This product has no description yet.');
    await harness.navigateByUrl('/catalog/another-id');
    TestBed.tick();
    http
      .expectOne('/api/product/another-id')
      .flush({ ...product, description: '<b>Description</b>' });
    await settle(harness);
    expect(element.textContent).toContain('<b>Description</b>');
    expect(element.querySelector('b')).toBeNull();
  });

  it('follows the route ID and resets the title while loading another product', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    await harness.navigateByUrl('/catalog/another-id');
    TestBed.tick();
    expect(TestBed.inject(Title).getTitle()).toBe('Product | DS Catalog');
    expect(element.querySelector('h1')).toBeNull();
    http
      .expectOne('/api/product/another-id')
      .flush({ ...product, id: 'another-id', name: 'Monitor' });
    await settle(harness);
    expect(element.querySelector('h1')?.textContent).toContain('Monitor');
    expect(TestBed.inject(Title).getTitle()).toBe('Monitor | DS Catalog');
  });

  it.each(['', '?page=2&name=Keyboard&category=Peripherals'])(
    'BACK navigates to the catalog with the original query parameters: %s',
    async (query) => {
      const { harness, element } = await render(url + query);
      http.expectOne('/api/product/' + product.id).flush(product);
      await settle(harness);
      const back = Array.from(element.querySelectorAll('a')).find(
        (link) => link.textContent?.trim() === 'BACK',
      );
      expect(back?.getAttribute('href')).toBe('/catalog' + query);
      const navigation = firstValueFrom(
        TestBed.inject(Router).events.pipe(filter((event) => event instanceof NavigationEnd)),
      );
      back?.click();
      await navigation;
      TestBed.tick();
      expect(TestBed.inject(Router).url).toBe('/catalog' + query);
      http.expectOne('/api/product/categories').flush([]);
      const request = http.expectOne((request) => request.url === '/api/product');
      expect(request.request.params.get('page')).toBe(query ? '1' : '0');
      expect(request.request.params.get('name')).toBe(query ? 'Keyboard' : null);
      request.flush({ content: [], page: query ? 1 : 0, size: 8, totalElements: 0, totalPages: 0 });
      await settle(harness);
      expect(TestBed.inject(Title).getTitle()).toBe('Catalog | DS Catalog');
    },
  );
  async function authenticate() {
    const pending = TestBed.inject(AuthService).load();
    http
      .expectOne('/bff/me')
      .flush({ sub: 'ana', name: 'Ana', email: 'ana@example.com', roles: ['CUSTOMER'] });
    await pending;
  }

  function addButton(element: HTMLElement) {
    const button = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Add to cart'),
    );
    expect(button).toBeDefined();
    return button;
  }

  it('starts login when a visitor tries to add to cart', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    addButton(element)?.click();
    expect(login).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
    expect(TestBed.inject(Cart).items()).toEqual([]);
  });

  it('adds a product and notifies the user without leaving the page', async () => {
    await authenticate();
    const messages = vi.spyOn(TestBed.inject(MessageService), 'add');
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    const add = vi.spyOn(TestBed.inject(Cart), 'add');
    addButton(element)?.click();
    expect(add).toHaveBeenCalledExactlyOnceWith(product.id);
    expect(TestBed.inject(Cart).totalQuantity()).toBe(1);
    expect(messages).toHaveBeenCalledWith(
      expect.objectContaining({ severity: 'success', detail: 'Product added to cart' }),
    );
    expect(TestBed.inject(Router).url).toBe(url);
  });

  it.each([
    ['limit-products', 'Your cart can contain up to 50 different products.'],
    ['limit-quantity', 'You can add up to 10,000 units of each product.'],
  ] as const)('explains when the limit is reached: %s', async (result, detail) => {
    await authenticate();
    const messages = vi.spyOn(TestBed.inject(MessageService), 'add');
    vi.spyOn(TestBed.inject(Cart), 'add').mockReturnValue(result);
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    addButton(element)?.click();
    expect(messages).toHaveBeenCalledWith(expect.objectContaining({ severity: 'error', detail }));
  });

  it('disables adding until stock loads and shows Out of stock when unavailable', async () => {
    const { harness, element } = await render(url, false);
    http.expectOne('/api/product/' + product.id).flush(product);
    await vi.waitFor(() => {
      harness.detectChanges();
      expect(element.querySelector('h1')).not.toBeNull();
    });
    expect(element.textContent).toContain('Checking stock');
    expect(element.querySelector('button')?.disabled).toBe(true);
    http
      .expectOne('/api/inventory?skuCode=' + product.id)
      .flush([{ skuCode: product.id, quantity: 0, inStock: false }]);
    await settle(harness);
    const button = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Out of stock'),
    );
    expect(button?.disabled).toBe(true);
  });

  it('shows stock ProblemDetail, blocks adding and allows retrying', async () => {
    const { harness, element } = await render(url, false);
    http.expectOne('/api/product/' + product.id).flush(product);
    await vi.waitFor(() => {
      harness.detectChanges();
      expect(element.querySelector('h1')).not.toBeNull();
    });
    http
      .expectOne('/api/inventory?skuCode=' + product.id)
      .flush({ detail: 'Stock unavailable.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Stock unavailable.');
    expect(addButton(element)?.disabled).toBe(true);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Check stock again'))
      ?.click();
    TestBed.tick();
    http
      .expectOne('/api/inventory?skuCode=' + product.id)
      .flush([{ skuCode: product.id, quantity: 2, inStock: true }]);
    await settle(harness);
    expect(addButton(element)?.disabled).toBe(false);
  });

  it('treats missing stock as unavailable without allowing additions', async () => {
    const { harness, element } = await render(url, false);
    http.expectOne('/api/product/' + product.id).flush(product);
    await vi.waitFor(() => {
      harness.detectChanges();
      expect(element.querySelector('h1')).not.toBeNull();
    });
    http.expectOne('/api/inventory?skuCode=' + product.id).flush([]);
    await settle(harness);
    expect(element.textContent).not.toContain('Unable to check stock');
    expect(element.querySelector('p-message')).toBeNull();
    const button = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Out of stock'),
    );
    expect(button?.disabled).toBe(true);
  });

  it('does not add beyond available stock and reports the quantity', async () => {
    await authenticate();
    const messages = vi.spyOn(TestBed.inject(MessageService), 'add');
    const { harness, element } = await render(url, false);
    http.expectOne('/api/product/' + product.id).flush(product);
    await vi.waitFor(() => {
      harness.detectChanges();
      expect(element.querySelector('h1')).not.toBeNull();
    });
    http
      .expectOne('/api/inventory?skuCode=' + product.id)
      .flush([{ skuCode: product.id, quantity: 2, inStock: true }]);
    await settle(harness);
    addButton(element)?.click();
    addButton(element)?.click();
    addButton(element)?.click();
    expect(TestBed.inject(Cart).totalQuantity()).toBe(2);
    expect(messages).toHaveBeenLastCalledWith(
      expect.objectContaining({
        severity: 'error',
        detail: 'Only 2 in stock. You already have 2 in your cart.',
      }),
    );
  });
});
