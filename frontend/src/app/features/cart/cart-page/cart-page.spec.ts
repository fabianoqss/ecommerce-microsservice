import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { MessageService } from 'primeng/api';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../../core/auth/auth.service';
import { AUTH_LOCATION } from '../../../core/auth/auth-location';
import { Cart } from '../../../core/cart/cart';
import { CartStore } from '../../../core/cart/cart-store';
import { LocalStorageCartStore } from '../../../core/cart/local-storage-cart-store';
import { routes } from '../../../app.routes';

@Component({ template: '' })
class Destination {}

const product = {
  id: 'a',
  name: 'Teclado',
  description: '',
  price: 12.5,
  category: '',
  imageUrl: null,
  version: 0,
};

describe('CartPage', () => {
  let http: HttpTestingController;
  let cart: Cart;
  const login = vi.fn();

  beforeEach(async () => {
    localStorage.clear();
    login.mockReset();
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'checkout', component: Destination }, ...routes]),
        { provide: CartStore, useClass: LocalStorageCartStore },
        { provide: AUTH_LOCATION, useValue: { assign: login } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const pending = TestBed.inject(AuthService).load();
    http
      .expectOne('/bff/me')
      .flush({ sub: 'ana', name: 'Ana', email: 'ana@example.com', roles: ['CUSTOMER'] });
    await pending;
    cart = TestBed.inject(Cart);
  });
  afterEach(() => {
    try {
      http.verify();
    } finally {
      localStorage.clear();
      TestBed.resetTestingModule();
    }
  });

  async function render() {
    const harness = await RouterTestingHarness.create('/cart');
    TestBed.tick();
    const element = harness.routeNativeElement;
    if (!element) throw new Error('Página ausente');
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function checkout(element: HTMLElement) {
    const button = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Continuar para o pedido'),
    );
    if (!button) throw new Error('Botão de checkout ausente');
    return button;
  }

  it('mostra estado vazio com link para catálogo', async () => {
    const { element } = await render();
    expect(element.textContent).toContain('Seu carrinho está vazio');
    expect(
      Array.from(element.querySelectorAll('a'))
        .find((a) => a.textContent?.includes('Ver catálogo'))
        ?.getAttribute('href'),
    ).toBe('/catalog');
    http.expectNone((r) => r.url.startsWith('/api/'));
    expect(document.title).toBe('Carrinho | DS Catalog');
  });

  it('exige autenticação ao navegar diretamente para carrinho', async () => {
    TestBed.inject(AuthService).clear();
    await RouterTestingHarness.create('/cart');
    expect(login).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
    http.expectNone((r) => r.url.startsWith('/api/'));
  });

  it('mostra carregamento, duas linhas, subtotal e navega para checkout', async () => {
    cart.add('a', 2);
    cart.add('b', 3);
    const { harness, element } = await render();
    expect(element.querySelector('p-skeleton')).not.toBeNull();
    expect(checkout(element).disabled).toBe(true);
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse', price: 10 });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    expect(element.textContent).toContain('Teclado');
    expect(element.textContent).toContain('Mouse');
    expect(element.textContent).toContain('R$ 25,00');
    expect(element.textContent).toContain('R$ 30,00');
    expect(element.querySelector('aside')?.textContent).toContain('R$ 55,00');
    expect(checkout(element).disabled).toBe(false);
    checkout(element).click();
    await settle(harness);
    expect(TestBed.inject(Router).url).toBe('/checkout');
  });

  it('altera quantidade e remove pelo componente de apresentação', async () => {
    cart.add('a', 2);
    const { harness, element } = await render();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(harness);
    const setQuantity = vi.spyOn(cart, 'setQuantity');
    const remove = vi.spyOn(cart, 'remove');
    const input = element.querySelector('input');
    if (!input) throw new Error('Quantidade ausente');
    input.value = '3';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('blur'));
    await settle(harness);
    expect(setQuantity).toHaveBeenCalledWith('a', 3);
    expect(element.querySelector('aside')?.textContent).toContain('R$ 37,50');
    http.expectNone((r) => r.url.startsWith('/api/'));
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Remover'))
      ?.click();
    await settle(harness);
    expect(remove).toHaveBeenCalledWith('a');
    expect(element.textContent).toContain('Seu carrinho está vazio');
  });

  it.each([
    ['Home', 1, 'R$ 12,50'],
    ['End', 5, 'R$ 62,50'],
  ] as const)(
    'sincroniza quantidade e subtotal com a tecla %s',
    async (key, quantity, subtotal) => {
      cart.add('a', 2);
      const { harness, element } = await render();
      http.expectOne('/api/product/a').flush(product);
      http
        .expectOne('/api/inventory?skuCode=a')
        .flush([{ skuCode: 'a', quantity: 5, inStock: true }]);
      await settle(harness);
      const input = element.querySelector('input');
      if (!input) throw new Error('Quantidade ausente');
      input.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      input.dispatchEvent(new Event('blur'));
      await settle(harness);
      expect(cart.items()).toEqual([{ productId: 'a', quantity }]);
      expect(input.value).toBe(String(quantity));
      expect(element.querySelector('aside')?.textContent).toContain(subtotal);
      http.expectNone((request) => request.url.startsWith('/api/'));
    },
  );

  it('mantém a outra linha, seu foco e subtotal ao remover um de dois produtos sem requisições', async () => {
    cart.add('a', 2);
    cart.add('b', 3);
    const { harness, element } = await render();
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse', price: 10 });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    document.body.appendChild(harness.fixture.nativeElement);
    const remaining = element.querySelector('article[aria-label="Mouse"]');
    const input = remaining?.querySelector('input');
    input?.focus();
    expect(document.activeElement).toBe(input);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.getAttribute('aria-label') === 'Remover Teclado')
      ?.click();
    TestBed.tick();
    harness.detectChanges();
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('article[aria-label="Mouse"]')).toBe(remaining);
    expect(input).toBeDefined();
    expect(element.querySelector('article[aria-label="Mouse"] input')).toBe(input);
    expect(document.activeElement).toBe(input);
    expect(element.querySelector('aside')?.textContent).toContain('R$ 30,00');
    http.expectNone((request) => request.url.startsWith('/api/'));
  });

  it('mostra linhas válidas, subtotal parcial e produto removível junto ao ProblemDetail', async () => {
    cart.add('a', 2);
    cart.add('b');
    const { harness, element } = await render();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/product/b')
      .flush(
        { detail: 'Produto temporariamente indisponível.' },
        { status: 503, statusText: 'Unavailable' },
      );
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    expect(element.querySelector('article[aria-label="Teclado"]')?.textContent).toContain(
      'R$ 25,00',
    );
    expect(element.textContent).toContain('Não foi possível carregar este produto.');
    expect(element.textContent).toContain('Produto temporariamente indisponível.');
    expect(element.querySelector('aside')?.textContent).toContain('R$ 25,00');
    expect(checkout(element).disabled).toBe(true);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Tentar novamente'))
      ?.click();
    TestBed.tick();
    harness.detectChanges();
    expect(element.textContent).not.toContain('Produto temporariamente indisponível.');
    expect(element.querySelector('article[aria-label="Teclado"]')).not.toBeNull();
    expect(element.querySelector('p-skeleton')).toBeNull();
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse' });
    http.expectNone((request) => request.url.startsWith('/api/'));
    await settle(harness);
    expect(checkout(element).disabled).toBe(false);
  });

  it.each(['unavailable', 'out-of-stock', 'insufficient-stock'])(
    'bloqueia checkout para %s',
    async (status) => {
      cart.add('a', 2);
      const { harness, element } = await render();
      const request = http.expectOne('/api/product/a');
      if (status === 'unavailable') request.flush({}, { status: 404, statusText: 'Not Found' });
      else request.flush(product);
      http.expectOne('/api/inventory?skuCode=a').flush([
        {
          skuCode: 'a',
          quantity: status === 'out-of-stock' ? 0 : 1,
          inStock: status !== 'out-of-stock',
        },
      ]);
      await settle(harness);
      expect(element.textContent).toContain(
        status === 'unavailable'
          ? 'Produto indisponível'
          : status === 'out-of-stock'
            ? 'Sem estoque'
            : 'Apenas 1 em estoque',
      );
      expect(checkout(element).disabled).toBe(true);
      expect(element.querySelector('aside')?.textContent).toContain('R$ 0,00');
    },
  );

  it.each(['product', 'inventory'])(
    'permite remover itens mesmo durante falha de %s',
    async (failing) => {
      cart.add('a');
      const { harness, element } = await render();
      const productRequest = http.expectOne('/api/product/a');
      const inventoryRequest = http.expectOne('/api/inventory?skuCode=a');
      if (failing === 'product') {
        productRequest.flush(
          { detail: 'Catálogo indisponível.' },
          { status: 503, statusText: 'Unavailable' },
        );
        inventoryRequest.flush([]);
      } else {
        productRequest.flush(product);
        inventoryRequest.flush(
          { detail: 'Estoque indisponível.' },
          { status: 503, statusText: 'Unavailable' },
        );
      }
      await settle(harness);
      expect(checkout(element).disabled).toBe(true);
      const remove = Array.from(element.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Remover'),
      );
      expect(remove).toBeDefined();
      remove?.click();
      await settle(harness);
      expect(cart.items()).toEqual([]);
      expect(element.textContent).toContain('Seu carrinho está vazio');
    },
  );

  it('mostra ProblemDetail e tenta novamente após falha de produto', async () => {
    cart.add('a');
    const { harness, element } = await render();
    http
      .expectOne('/api/product/a')
      .flush({ detail: 'Catálogo indisponível.' }, { status: 503, statusText: 'Unavailable' });
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Catálogo indisponível.');
    expect(checkout(element).disabled).toBe(true);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Tentar novamente'))
      ?.click();
    TestBed.tick();
    http.expectOne('/api/product/a').flush(product);
    http.expectNone((request) => request.url === '/api/inventory');
    await settle(harness);
    expect(element.querySelector('p-message')).toBeNull();
    expect(checkout(element).disabled).toBe(false);
  });
});
