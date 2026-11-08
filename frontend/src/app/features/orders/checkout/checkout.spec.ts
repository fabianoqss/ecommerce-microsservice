import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MessageService } from 'primeng/api';
import { routes } from '../../../app.routes';
import { AUTH_LOCATION } from '../../../core/auth/auth-location';
import { AuthService } from '../../../core/auth/auth.service';
import { Cart } from '../../../core/cart/cart';
import { CartStore } from '../../../core/cart/cart-store';
import { LocalStorageCartStore } from '../../../core/cart/local-storage-cart-store';

@Component({ template: '<h1>Destino da confirmação</h1>' })
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
const order = {
  id: 'pedido-1',
  status: 'PENDING',
  totalValue: 25,
  orderTime: '2026-11-08T10:30:00',
  items: [{ productId: 'a', productName: 'Teclado', quantity: 2, priceAtPurchase: 12.5 }],
};

describe('Checkout', () => {
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
        provideRouter([{ path: 'orders/:id/confirmation', component: Destination }, ...routes]),
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
    const harness = await RouterTestingHarness.create('/checkout');
    TestBed.tick();
    const element = harness.routeNativeElement;
    if (!element) throw new Error('Página ausente');
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function button(element: HTMLElement, label = 'Finalizar pedido') {
    const result = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes(label),
    );
    if (!result) throw new Error('Botão ausente: ' + label);
    return result;
  }

  async function ready() {
    cart.add('a', 2);
    const result = await render();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(result.harness);
    return result;
  }

  it('mostra estado vazio com link para catálogo sem buscar produtos', async () => {
    const { element } = await render();
    expect(element.textContent).toContain('Seu carrinho está vazio');
    expect(element.querySelector('a[href="/catalog"]')?.textContent).toContain('Ver catálogo');
    expect(element.querySelector('button')).toBeNull();
    http.expectNone((request) => request.url.startsWith('/api/'));
    expect(document.title).toBe('Pedido | DS Catalog');
  });

  it('exige autenticação ao navegar diretamente para checkout', async () => {
    TestBed.inject(AuthService).clear();
    await RouterTestingHarness.create('/checkout');
    expect(login).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
    http.expectNone((request) => request.url.startsWith('/api/'));
  });

  it('carrega produtos e estoque, mostra revisão somente leitura e total', async () => {
    cart.add('a', 2);
    cart.add('b', 3);
    const { harness, element } = await render();
    expect(element.querySelector('p-skeleton')).not.toBeNull();
    expect(button(element).disabled).toBe(true);
    http.expectOne('/api/product/a').flush(product);
    http.expectOne('/api/product/b').flush({ ...product, id: 'b', name: 'Mouse', price: 10 });
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    const lines = element.querySelector('ul');
    expect(lines?.textContent).toContain('Teclado');
    expect(lines?.textContent).toContain('Mouse');
    expect(lines?.textContent).toContain('Quantidade: 2');
    expect(lines?.textContent).toContain('Quantidade: 3');
    expect(lines?.textContent).toContain('R$ 12,50');
    expect(lines?.textContent).toContain('R$ 25,00');
    expect(lines?.textContent).toContain('R$ 30,00');
    expect(element.querySelector('aside')?.textContent).toContain('R$ 55,00');
    expect(element.querySelector('input, p-inputnumber')).toBeNull();
    expect(button(element).disabled).toBe(false);
    expect(element.querySelector('a[href="/cart"]')?.textContent).toContain('Voltar ao carrinho');
  });

  it.each([
    ['unavailable', 'Produto indisponível'],
    ['out-of-stock', 'Sem estoque'],
    ['insufficient-stock', 'Apenas 1 em estoque'],
    ['error', 'Não foi possível carregar este produto.'],
  ])('bloqueia envio e identifica linha com status %s', async (status, message) => {
    cart.add('a', 2);
    const { harness, element } = await render();
    const request = http.expectOne('/api/product/a');
    if (status === 'unavailable') request.flush({}, { status: 404, statusText: 'Not Found' });
    else if (status === 'error')
      request.flush(
        { detail: 'Catálogo indisponível.' },
        { status: 503, statusText: 'Unavailable' },
      );
    else request.flush(product);
    http.expectOne('/api/inventory?skuCode=a').flush([
      {
        skuCode: 'a',
        quantity: status === 'out-of-stock' ? 0 : 1,
        inStock: status !== 'out-of-stock',
      },
    ]);
    await settle(harness);
    expect(element.textContent).toContain(message);
    expect(button(element).disabled).toBe(true);
    button(element).dispatchEvent(new MouseEvent('click', { bubbles: true }));
    http.expectNone('/api/order');
    expect(cart.totalQuantity()).toBe(2);
  });

  it('mostra ProblemDetail de carregamento e permite tentar novamente', async () => {
    cart.add('a', 2);
    const { harness, element } = await render();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush({ detail: 'Estoque indisponível.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Estoque indisponível.');
    expect(button(element).disabled).toBe(true);
    button(element, 'Tentar novamente').click();
    TestBed.tick();
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(harness);
    expect(button(element).disabled).toBe(false);
    expect(element.querySelector('p-message')).toBeNull();
  });

  it('envia somente ids e quantidades com UUID v4 e impede duplo clique antes de renderizar', async () => {
    const { harness, element } = await ready();
    const submit = button(element);
    submit.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    submit.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const request = http.expectOne('/api/order');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ items: [{ productId: 'a', quantity: 2 }] });
    expect(request.request.headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    harness.detectChanges();
    expect(button(element).disabled).toBe(true);
    expect(cart.totalQuantity()).toBe(2);
    request.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
    expect(cart.items()).toEqual([]);
    expect(localStorage.getItem('cart:ana')).toBe('[]');
    expect(TestBed.inject(Router).url).toBe('/orders/pedido-1/confirmation');
  });

  it.each([400, 409, 500, 503, 0])(
    'mantém carrinho e chave após HTTP %s e permite retry',
    async (status) => {
      const { harness, element } = await ready();
      button(element).click();
      const first = http.expectOne('/api/order');
      const key = first.request.headers.get('Idempotency-Key');
      if (status === 0) first.error(new ProgressEvent('error'));
      else
        first.flush(
          { detail: 'Não foi possível reservar o estoque.' },
          { status, statusText: 'Erro' },
        );
      await settle(harness);
      expect(element.querySelector('p-message')?.textContent).toContain(
        status === 0
          ? 'Não foi possível finalizar o pedido. Tente novamente.'
          : 'Não foi possível reservar o estoque.',
      );
      expect(cart.items()).toEqual([{ productId: 'a', quantity: 2 }]);
      expect(button(element).disabled).toBe(false);
      expect(TestBed.inject(Router).url).toBe('/checkout');
      button(element).click();
      const retry = http.expectOne('/api/order');
      expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
      expect(retry.request.body).toEqual({ items: [{ productId: 'a', quantity: 2 }] });
      retry.flush(order, { status: 201, statusText: 'Created' });
      await settle(harness);
      expect(cart.items()).toEqual([]);
    },
  );

  it('troca chave após 422 e pede revisão mantendo o carrinho', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const first = http.expectOne('/api/order');
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(
      { detail: 'Chave já utilizada.' },
      { status: 422, statusText: 'Unprocessable Content' },
    );
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain(
      'Seu carrinho mudou. Revise e tente novamente.',
    );
    expect(cart.totalQuantity()).toBe(2);
    button(element).click();
    const retry = http.expectOne('/api/order');
    expect(retry.request.headers.get('Idempotency-Key')).not.toBe(key);
    retry.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
  });

  it('troca chave quando o snapshot do carrinho muda antes do próximo clique', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const first = http.expectOne('/api/order');
    const key = first.request.headers.get('Idempotency-Key');
    first.flush({ detail: 'Tente novamente.' }, { status: 409, statusText: 'Conflict' });
    await settle(harness);
    cart.setQuantity('a', 3);
    button(element).dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const retry = http.expectOne('/api/order');
    expect(retry.request.headers.get('Idempotency-Key')).not.toBe(key);
    expect(retry.request.body).toEqual({ items: [{ productId: 'a', quantity: 3 }] });
    retry.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
  });

  it('não limpa carrinho nem navega após resposta 200 inesperada', async () => {
    const { harness, element } = await ready();
    button(element).click();
    http.expectOne('/api/order').flush(order);
    await settle(harness);
    expect(cart.totalQuantity()).toBe(2);
    expect(TestBed.inject(Router).url).toBe('/checkout');
    expect(element.querySelector('p-message')?.textContent).toContain(
      'Não foi possível finalizar o pedido. Tente novamente.',
    );
    expect(button(element).disabled).toBe(false);
  });

  it.each([409, 0])(
    'reutiliza a chave após HTTP %s ao sair e recriar o checkout',
    async (status) => {
      const { harness, element } = await ready();
      button(element).click();
      const first = http.expectOne('/api/order');
      const key = first.request.headers.get('Idempotency-Key');
      if (status === 0) first.error(new ProgressEvent('error'));
      else first.flush({ detail: 'Em processamento.' }, { status, statusText: 'Conflict' });
      await settle(harness);
      await harness.navigateByUrl('/orders/fora/confirmation');
      await harness.navigateByUrl('/checkout');
      TestBed.tick();
      http.expectOne('/api/product/a').flush(product);
      http
        .expectOne('/api/inventory?skuCode=a')
        .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
      await settle(harness);
      const recreated = harness.routeNativeElement;
      if (!recreated) throw new Error('Página ausente');
      button(recreated).click();
      const retry = http.expectOne('/api/order');
      retry.flush(order, { status: 201, statusText: 'Created' });
      expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
      await settle(harness);
    },
  );

  it('remove somente as quantidades enviadas quando o carrinho muda durante o POST', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const request = http.expectOne('/api/order');
    cart.add('a', 3);
    cart.add('b', 4);
    expect(request.request.body).toEqual({ items: [{ productId: 'a', quantity: 2 }] });
    request.flush(order, { status: 201, statusText: 'Created' });
    expect(cart.items()).toEqual([
      { productId: 'a', quantity: 3 },
      { productId: 'b', quantity: 4 },
    ]);
    expect(JSON.parse(localStorage.getItem('cart:ana') ?? 'null')).toEqual([
      { productId: 'a', quantity: 3 },
      { productId: 'b', quantity: 4 },
    ]);
    await settle(harness);
  });

  it('processa o 201 após destruir o checkout sem navegar para a confirmação', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const request = http.expectOne('/api/order');
    await harness.navigateByUrl('/orders/fora/confirmation');
    request.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
    expect(cart.items()).toEqual([]);
    expect(localStorage.getItem('cart:ana')).toBe('[]');
    expect(TestBed.inject(Router).url).toBe('/orders/fora/confirmation');
  });

  it('reutiliza a chave ao recriar o checkout enquanto o primeiro POST está pendente', async () => {
    const { harness, element } = await ready();
    button(element).click();
    const first = http.expectOne('/api/order');
    const key = first.request.headers.get('Idempotency-Key');
    await harness.navigateByUrl('/orders/fora/confirmation');
    await harness.navigateByUrl('/checkout');
    TestBed.tick();
    http.expectOne('/api/product/a').flush(product);
    http
      .expectOne('/api/inventory?skuCode=a')
      .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
    await settle(harness);
    const recreated = harness.routeNativeElement;
    if (!recreated) throw new Error('Página ausente');
    button(recreated).click();
    const retry = http.expectOne('/api/order');
    retry.flush({ detail: 'Em processamento.' }, { status: 409, statusText: 'Conflict' });
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    first.flush(order, { status: 201, statusText: 'Created' });
    await settle(harness);
    expect(cart.items()).toEqual([]);
    expect(TestBed.inject(Router).url).toBe('/checkout');
  });

  it('mostra confirmação em andamento sem exibir carrinho vazio enquanto a navegação aguarda', async () => {
    const { harness, element } = await ready();
    let releaseConfirmation: () => void = () => undefined;
    const confirmation = new Promise<typeof Destination>((resolve) => {
      releaseConfirmation = () => resolve(Destination);
    });
    TestBed.inject(Router).resetConfig([
      { path: 'orders/:id/confirmation', loadComponent: () => confirmation },
      ...routes,
    ]);
    try {
      button(element).click();
      http.expectOne('/api/order').flush(order, { status: 201, statusText: 'Created' });
      harness.detectChanges();
      expect(element.querySelector('[role="status"]')?.textContent).toContain(
        'Pedido realizado. Abrindo a confirmação…',
      );
      expect(element.textContent).not.toContain('Seu carrinho está vazio');
      expect(element.querySelector('button')).toBeNull();
    } finally {
      releaseConfirmation();
      await settle(harness);
    }
  });

  it.each([false, true])(
    'usa chave nova após 201 do mesmo snapshot, com checkout destruído: %s',
    async (destroyed) => {
      const { harness, element } = await ready();
      button(element).click();
      const first = http.expectOne('/api/order');
      const key = first.request.headers.get('Idempotency-Key');
      if (destroyed) await harness.navigateByUrl('/orders/fora/confirmation');
      first.flush(order, { status: 201, statusText: 'Created' });
      await settle(harness);
      cart.add('a', 2);
      await harness.navigateByUrl('/checkout');
      TestBed.tick();
      http.expectOne('/api/product/a').flush(product);
      http
        .expectOne('/api/inventory?skuCode=a')
        .flush([{ skuCode: 'a', quantity: 10, inStock: true }]);
      await settle(harness);
      const recreated = harness.routeNativeElement;
      if (!recreated) throw new Error('Página ausente');
      button(recreated).click();
      const next = http.expectOne('/api/order');
      next.flush(order, { status: 201, statusText: 'Created' });
      expect(next.request.headers.get('Idempotency-Key')).not.toBe(key);
      await settle(harness);
    },
  );

  it.each([
    [404, 'Produto indisponível'],
    [503, 'Produto'],
  ])('identifica de forma única produtos sem dados após HTTP %s', async (status, label) => {
    cart.add('a');
    cart.add('b');
    const { harness, element } = await render();
    for (const id of ['a', 'b']) {
      http.expectOne('/api/product/' + id).flush({}, { status, statusText: 'Erro' });
    }
    http.expectOne('/api/inventory?skuCode=a&skuCode=b').flush([
      { skuCode: 'a', quantity: 10, inStock: true },
      { skuCode: 'b', quantity: 10, inStock: true },
    ]);
    await settle(harness);
    for (const id of ['a', 'b']) {
      const article = element.querySelector(`article[aria-label="${label} (${id})"]`);
      expect(article).not.toBeNull();
      expect(article?.querySelector('h2')?.textContent).toBe(`${label} (${id})`);
      if (status === 503) {
        expect(article?.textContent).toContain('Não foi possível carregar este produto.');
        expect(article?.textContent).not.toContain('Produto indisponível');
      }
    }
  });
});
