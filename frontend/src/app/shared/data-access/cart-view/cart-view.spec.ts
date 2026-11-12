import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CartItem } from '../../../core/cart/cart-item';
import { loadCartView } from './cart-view';

const product = {
  id: 'a',
  name: 'Keyboard',
  description: '',
  price: 12.5,
  category: '',
  imageUrl: null,
  version: 0,
};

describe('loadCartView', () => {
  let http: HttpTestingController;
  const items = signal<CartItem[]>([]);
  const stable = () => TestBed.inject(ApplicationRef).whenStable();
  const create = () => TestBed.runInInjectionContext(() => loadCartView(items));

  beforeEach(() => {
    items.set([]);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function stock(quantity = 10, inStock = true) {
    http.expectOne('/api/inventory?skuCode=a').flush([{ skuCode: 'a', quantity, inStock }]);
  }

  it('does not query APIs for an empty cart and blocks checkout', () => {
    const view = create();
    TestBed.tick();
    http.expectNone(() => true);
    expect(view.lines()).toEqual([]);
    expect(view.subtotal()).toBe(0);
    expect(view.isLoading()).toBe(false);
    expect(view.canCheckout()).toBe(false);
  });

  it('combines products and stock, totals valid lines and does not query again when quantities change', async () => {
    items.set([
      { productId: 'a', quantity: 2 },
      { productId: 'b', quantity: 3 },
    ]);
    const view = create();
    TestBed.tick();
    expect(view.isLoading()).toBe(true);
    expect(view.canCheckout()).toBe(false);
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse', price: 10 });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await stable();
    expect(view.subtotal()).toBe(55);
    expect(view.canCheckout()).toBe(true);
    items.set([
      { productId: 'a', quantity: 4 },
      { productId: 'b', quantity: 3 },
    ]);
    TestBed.tick();
    await stable();
    http.expectNone(() => true);
    expect(view.lines()[0].quantity).toBe(4);
    expect(view.subtotal()).toBe(80);
    expect(view.canCheckout()).toBe(true);
  });

  async function loadTwoProducts() {
    items.set([
      { productId: 'a', quantity: 2 },
      { productId: 'b', quantity: 3 },
    ]);
    const view = create();
    TestBed.tick();
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse', price: 10 });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await stable();
    return view;
  }

  it('removes a line without reloading or losing the other line data', async () => {
    const view = await loadTwoProducts();
    items.set([{ productId: 'b', quantity: 3 }]);
    TestBed.tick();
    expect(view.isLoading()).toBe(false);
    expect(view.lines()).toEqual([
      {
        productId: 'b',
        quantity: 3,
        stock: 10,
        status: 'ok',
        product: { ...product, id: 'b', name: 'Mouse', price: 10 },
      },
    ]);
    expect(view.subtotal()).toBe(30);
    expect(view.canCheckout()).toBe(true);
    http.expectNone(() => true);
  });

  it('fetches only the new product and stock without hiding loaded lines', async () => {
    const view = await loadTwoProducts();
    items.update((current) => [...current, { productId: 'c', quantity: 1 }]);
    TestBed.tick();
    expect(view.isLoading()).toBe(false);
    expect(view.lines().filter((line) => line.status === 'ok')).toHaveLength(2);
    expect(view.subtotal()).toBe(55);
    expect(view.canCheckout()).toBe(false);
    http.expectOne('/api/product/c').flush({ ...product, id: 'c' });
    http
      .expectOne('/api/inventory?skuCode=c')
      .flush([{ skuCode: 'c', quantity: 10, inStock: true }]);
    http.expectNone(() => true);
    await stable();
    expect(view.lines()).toHaveLength(3);
    expect(view.subtotal()).toBe(67.5);
    expect(view.canCheckout()).toBe(true);
  });

  it('reuses a removed product when it returns to the cart', async () => {
    const view = await loadTwoProducts();
    items.set([{ productId: 'a', quantity: 2 }]);
    TestBed.tick();
    items.update((current) => [...current, { productId: 'b', quantity: 1 }]);
    TestBed.tick();
    expect(view.isLoading()).toBe(false);
    expect(view.subtotal()).toBe(35);
    http.expectNone(() => true);
  });

  it('preserves valid lines when another product fails and retries only the failed product', async () => {
    items.set([
      { productId: 'a', quantity: 2 },
      { productId: 'b', quantity: 3 },
    ]);
    const view = create();
    TestBed.tick();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/product/b')
      .flush({ detail: 'Mouse unavailable.' }, { status: 503, statusText: 'Unavailable' });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await stable();
    expect(view.lines()[0]).toMatchObject({ product, status: 'ok' });
    expect(view.lines()[1]).toMatchObject({ product: null, status: 'error' });
    expect(view.subtotal()).toBe(25);
    expect(view.error()).toMatchObject({ error: { detail: 'Mouse unavailable.' } });
    expect(view.canCheckout()).toBe(false);
    view.reload();
    TestBed.tick();
    expect(view.error()).toBeUndefined();
    expect(view.isLoading()).toBe(false);
    expect(view.lines()[0]).toMatchObject({ product, status: 'ok' });
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', price: 10 });
    http.expectNone(() => true);
    await stable();
    expect(view.subtotal()).toBe(55);
    expect(view.canCheckout()).toBe(true);
  });

  it('retries only failed stock and preserves successful stock and products', async () => {
    const view = await loadTwoProducts();
    items.update((current) => [...current, { productId: 'c', quantity: 1 }]);
    TestBed.tick();
    http.expectOne('/api/product/c').flush({ ...product, id: 'c' });
    http
      .expectOne('/api/inventory?skuCode=c')
      .flush({ detail: 'Stock unavailable.' }, { status: 503, statusText: 'Unavailable' });
    await stable();
    expect(view.lines().map((line) => line.status)).toEqual(['ok', 'ok', 'error']);
    expect(view.subtotal()).toBe(55);
    expect(view.canCheckout()).toBe(false);
    view.reload();
    TestBed.tick();
    expect(view.error()).toBeUndefined();
    http
      .expectOne('/api/inventory?skuCode=c')
      .flush([{ skuCode: 'c', quantity: 10, inStock: true }]);
    http.expectNone(() => true);
    await stable();
    expect(view.subtotal()).toBe(67.5);
    expect(view.canCheckout()).toBe(true);
  });

  it.each([
    [0, false, 'out-of-stock'],
    [5, false, 'out-of-stock'],
    [0, true, 'out-of-stock'],
    [1, true, 'insufficient-stock'],
  ] as const)(
    'blocks stock %s/%s with status %s without adding the line total',
    async (quantity, inStock, status) => {
      items.set([{ productId: 'a', quantity: 2 }]);
      const view = create();
      TestBed.tick();
      http.expectOne('/api/product/a').flush(product);
      stock(quantity, inStock);
      await stable();
      expect(view.lines()[0].status).toBe(status);
      expect(view.subtotal()).toBe(0);
      expect(view.canCheckout()).toBe(false);
    },
  );

  it('treats an omitted SKU as out of stock', async () => {
    items.set([{ productId: 'a', quantity: 1 }]);
    const view = create();
    TestBed.tick();
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/inventory?skuCode=a').flush([]);
    await stable();
    expect(view.lines()[0]).toMatchObject({ stock: null, status: 'out-of-stock' });
    expect(view.canCheckout()).toBe(false);
  });

  it('preserves a 404 product as removable without turning it into a global error', async () => {
    items.set([{ productId: 'a', quantity: 1 }]);
    const view = create();
    TestBed.tick();
    http.expectOne('/api/product/a').flush({}, { status: 404, statusText: 'Not Found' });
    stock();
    await stable();
    expect(view.lines()).toEqual([
      { productId: 'a', quantity: 1, product: null, stock: 10, status: 'unavailable' },
    ]);
    expect(view.error()).toBeFalsy();
    expect(view.canCheckout()).toBe(false);
  });

  it.each(['product', 'inventory'])('exposes %s failures and allows reloading', async (failing) => {
    items.set([{ productId: 'a', quantity: 1 }]);
    const view = create();
    TestBed.tick();
    const p = http.expectOne('/api/product/a');
    const i = http.expectOne('/api/inventory?skuCode=a');
    if (failing === 'product') {
      p.flush({ detail: 'Products unavailable.' }, { status: 503, statusText: 'Unavailable' });
      i.flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    } else {
      p.flush(product);
      i.flush({ detail: 'Stock unavailable.' }, { status: 503, statusText: 'Unavailable' });
    }
    await stable();
    expect(view.error()).toBeTruthy();
    expect(view.canCheckout()).toBe(false);
    view.reload();
    TestBed.tick();
    expect(view.canCheckout()).toBe(false);
    expect(view.error()).toBeUndefined();
    if (failing === 'product') {
      http.expectOne('/api/product/a').flush(product);
      http.expectNone((request) => request.url === '/api/inventory');
    } else {
      http.expectNone('/api/product/a');
      stock();
    }
    await stable();
    expect(view.error()).toBeUndefined();
    expect(view.canCheckout()).toBe(true);
  });

  it('follows removal and clearing without keeping old lines or totals', async () => {
    items.set([{ productId: 'a', quantity: 1 }]);
    const view = create();
    TestBed.tick();
    http.expectOne('/api/product/a').flush(product);
    stock();
    await stable();
    items.set([]);
    expect(view.canCheckout()).toBe(false);
    expect(view.lines()).toEqual([]);
    expect(view.subtotal()).toBe(0);
    TestBed.tick();
    await stable();
    http.expectNone(() => true);
  });
});
