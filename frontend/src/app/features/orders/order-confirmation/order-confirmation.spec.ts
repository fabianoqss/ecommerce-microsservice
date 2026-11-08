import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MessageService } from 'primeng/api';
import { routes } from '../../../app.routes';
import { AUTH_LOCATION } from '../../../core/auth/auth-location';
import { AuthService } from '../../../core/auth/auth.service';
import { CartStore } from '../../../core/cart/cart-store';
import { LocalStorageCartStore } from '../../../core/cart/local-storage-cart-store';
import { Order } from '../../../shared/data-access/order/order';

const order: Order = {
  id: 'd715a7c9-d942-4c65-92eb-704c797dc6ee',
  status: 'PENDING',
  totalValue: 42.75,
  orderTime: '2026-03-26T14:30:00',
  items: [
    { productId: 'a', productName: 'Teclado comprado', quantity: 2, priceAtPurchase: 12.5 },
    { productId: 'b', productName: 'Mouse comprado', quantity: 1, priceAtPurchase: 15 },
  ],
};
const url = '/orders/' + order.id + '/confirmation';

describe('OrderConfirmation', () => {
  let http: HttpTestingController;
  const login = vi.fn();

  beforeEach(async () => {
    localStorage.clear();
    login.mockReset();
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes, withComponentInputBinding()),
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
  });

  afterEach(() => {
    try {
      http.expectNone((request) => request.url.startsWith('/api/product'));
      http.verify();
    } finally {
      localStorage.clear();
      TestBed.resetTestingModule();
    }
  });

  async function render(path = url) {
    const harness = await RouterTestingHarness.create(path);
    TestBed.tick();
    const element = harness.routeNativeElement;
    if (!element) throw new Error('Página ausente');
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  it('mostra carregamento acessível enquanto busca o pedido', async () => {
    const { harness, element } = await render();
    expect(element.querySelector('p-skeleton')).not.toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    expect(element.querySelector('[role="status"]')?.textContent).toContain('Carregando pedido.');
    const request = http.expectOne('/api/order/' + order.id);
    expect(request.request.method).toBe('GET');
    request.flush(order);
    await settle(harness);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('false');
  });

  it('mostra número, snapshots dos itens, total do servidor e retorno ao catálogo', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush(order);
    await settle(harness);
    expect(document.title).toBe('Pedido realizado | DS Catalog');
    expect(element.querySelector('h1')?.textContent?.trim()).toBe('Pedido realizado!');
    expect(element.textContent).toContain(order.id);
    const lines = element.querySelector('ul[aria-label="Produtos no pedido"]');
    expect(lines?.querySelectorAll('li')).toHaveLength(2);
    const keyboard = lines?.querySelector('li');
    expect(keyboard?.textContent).toContain('Teclado comprado');
    expect(keyboard?.textContent).toContain('Quantidade: 2');
    expect(keyboard?.textContent).toContain('Preço unitário: R$ 12,50');
    expect(lines?.textContent).toContain('Mouse comprado');
    expect(lines?.textContent).toContain('Quantidade: 1');
    expect(lines?.textContent).toContain('Preço unitário: R$ 15,00');
    expect(element.querySelector('aside')?.textContent).toContain('R$ 42,75');
    expect(element.querySelector('a[href="/catalog"]')?.textContent).toContain(
      'Continuar comprando',
    );
    expect(element.querySelector('input, p-inputnumber')).toBeNull();
  });

  it('apresenta PENDING como processamento normal com orientação sobre o estoque', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush(order);
    await settle(harness);
    expect(element.textContent).toContain('Processando');
    expect(element.textContent).toContain('Estamos confirmando a reserva do estoque.');
    expect(element.querySelector('p-message[severity="error"]')).toBeNull();
  });

  it('mostra outro status sem orientação de processamento pendente', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush({ ...order, status: 'CONFIRMED' });
    await settle(harness);
    expect(element.textContent).toContain('Confirmado');
    expect(element.textContent).not.toContain('Estamos confirmando a reserva do estoque.');
  });

  it.each([
    ['2026-03-26T14:30:00', '26/03/2026 14:30'],
    ['2026-01-01T00:05:00', '01/01/2026 00:05'],
    ['2026-03-08T02:30:00', '08/03/2026 02:30'],
    ['2026-12-31T23:59:59.123456', '31/12/2026 23:59'],
  ])('formata %s como data e hora locais sem mudar o horário', async (orderTime, expected) => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush({ ...order, orderTime });
    await settle(harness);
    const time = element.querySelector('time');
    expect(time?.textContent?.trim()).toBe(expected);
    expect(time?.getAttribute('datetime')).toBe(orderTime);
  });

  it('mostra pedido não encontrado no 404 sem expor o detalhe do servidor', async () => {
    const { harness, element } = await render();
    http
      .expectOne('/api/order/' + order.id)
      .flush({ detail: 'Pedido de outro cliente.' }, { status: 404, statusText: 'Not Found' });
    await settle(harness);
    expect(element.textContent).toContain('Pedido não encontrado.');
    expect(element.textContent).not.toContain('Pedido de outro cliente.');
    expect(element.textContent).not.toContain('Pedido realizado!');
    expect(element.querySelector('button')).toBeNull();
    expect(element.querySelector('a[href="/catalog"]')?.textContent).toContain(
      'Continuar comprando',
    );
  });

  it.each([
    'pedido-invalido',
    'd715a7c9-d942-4c65-92eb-704c797dc6eg',
    'd715a7c9d9424c6592eb704c797dc6ee',
  ])('rejeita id inválido %s sem chamar a API', async (id) => {
    const { harness, element } = await render('/orders/' + id + '/confirmation');
    await settle(harness);
    expect(element.textContent).toContain('Pedido não encontrado.');
    expect(element.textContent).not.toContain('Pedido realizado!');
    http.expectNone((request) => request.url.startsWith('/api/'));
  });

  it.each([
    [{ detail: 'Pedidos indisponíveis.' }, 'Pedidos indisponíveis.'],
    [{}, 'Não foi possível carregar o pedido. Tente novamente.'],
  ])('mostra ProblemDetail ou fallback e permite tentar novamente', async (problem, message) => {
    const { harness, element } = await render();
    http
      .expectOne('/api/order/' + order.id)
      .flush(problem, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message[severity="error"]')?.textContent).toContain(message);
    expect(element.textContent).not.toContain('Pedido realizado!');
    const retry = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Tentar novamente'),
    );
    expect(retry).toBeDefined();
    retry?.click();
    TestBed.tick();
    http.expectOne('/api/order/' + order.id).flush(order);
    await settle(harness);
    expect(element.querySelector('p-message[severity="error"]')).toBeNull();
    expect(element.querySelector('h1')?.textContent).toContain('Pedido realizado!');
  });

  it('acompanha mudanças do id e remove os dados anteriores ao receber um id inválido', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/order/' + order.id).flush(order);
    await settle(harness);
    const secondId = 'f715a7c9-d942-4c65-92eb-704c797dc6ee';
    await harness.navigateByUrl('/orders/' + secondId + '/confirmation');
    TestBed.tick();
    expect(element.textContent).not.toContain(order.id);
    http.expectOne('/api/order/' + secondId).flush({ ...order, id: secondId });
    await settle(harness);
    expect(element.textContent).toContain(secondId);
    await harness.navigateByUrl('/orders/invalido/confirmation');
    await settle(harness);
    expect(element.textContent).toContain('Pedido não encontrado.');
    expect(element.textContent).not.toContain(secondId);
    http.expectNone((request) => request.url.startsWith('/api/'));
  });

  it('exige login na navegação direta sem buscar o pedido', async () => {
    TestBed.inject(AuthService).clear();
    await RouterTestingHarness.create(url);
    expect(login).toHaveBeenCalledExactlyOnceWith('/oauth2/authorization/keycloak');
    http.expectNone((request) => request.url.startsWith('/api/'));
  });
});
