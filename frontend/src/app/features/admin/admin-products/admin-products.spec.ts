import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NavigationEnd, provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, firstValueFrom } from 'rxjs';
import { AdminProducts } from './admin-products';

describe('AdminProducts', () => {
  let http: HttpTestingController;
  const emptyPage = { content: [], page: 0, size: 4, totalElements: 0, totalPages: 0 };
  const products = Array.from({ length: 4 }, (_, index) => ({
    id: String(index),
    name: 'Produto ' + index,
    description: 'Descrição',
    price: 2779,
    category: 'Periféricos',
    imageUrl: null,
    version: 0,
  }));
  const productPage = { ...emptyPage, content: products, totalElements: 4, totalPages: 1 };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [{ path: 'admin/products', component: AdminProducts }],
          withComponentInputBinding(),
        ),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(url = '/admin/products', failCategories = false, loadProducts = true) {
    const harness = await RouterTestingHarness.create(url);
    TestBed.tick();
    const request = http.expectOne('/api/product/categories');
    if (failCategories) {
      request.flush({}, { status: 503, statusText: 'Unavailable' });
    } else {
      request.flush(['Periféricos', 'Monitores']);
    }
    if (loadProducts) {
      http.expectOne((req) => req.url === '/api/product').flush(emptyPage);
      await harness.fixture.whenStable();
    }
    harness.detectChanges();
    const element = harness.routeNativeElement;
    if (!element) {
      throw new Error('A página de produtos não foi renderizada');
    }
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  async function completeProducts(harness: RouterTestingHarness) {
    TestBed.tick();
    http.expectOne((req) => req.url === '/api/product').flush(emptyPage);
    await settle(harness);
  }

  function nextNavigation() {
    return firstValueFrom(
      TestBed.inject(Router).events.pipe(filter((event) => event instanceof NavigationEnd)),
    );
  }

  function queryParams() {
    const router = TestBed.inject(Router);
    return router.parseUrl(router.url).queryParams;
  }

  it('identifica a página com um título disponível para leitores de tela', async () => {
    const { element } = await render();
    const heading = element.querySelector('h1');
    expect(heading?.textContent).toBe('Produtos');
    expect(heading?.classList.contains('sr-only')).toBe(true);
    expect(heading?.hasAttribute('aria-hidden')).toBe(false);
  });

  it('mostra o botão ADICIONAR sem submeter formulário ou navegar', async () => {
    const { element } = await render();
    const button = element.querySelector('button');
    expect(button?.textContent?.trim()).toBe('ADICIONAR');
    expect(button?.type).toBe('button');
    button?.click();
    expect(TestBed.inject(Router).url).toBe('/admin/products');
  });

  it('preenche a busca com os filtros da URL e acompanha novas navegações', async () => {
    const { harness, element } = await render(
      '/admin/products?name=Teclado&category=Perif%C3%A9ricos',
    );
    expect(element.querySelector('input')?.value).toBe('Teclado');
    expect(element.querySelector('select')?.value).toBe('Periféricos');
    await harness.navigateByUrl('/admin/products?name=Monitor&category=Monitores');
    await completeProducts(harness);
    await harness.fixture.whenStable();
    expect(element.querySelector('input')?.value).toBe('Monitor');
    expect(element.querySelector('select')?.value).toBe('Monitores');
  });

  it('atualiza nome na URL preservando categoria e substituindo o histórico', async () => {
    const { harness, element } = await render('/admin/products?category=Perif%C3%A9ricos');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const field = element.querySelector('input');
    if (!field) throw new Error('Campo de nome ausente');
    const navigation = nextNavigation();
    field.value = ' Teclado ';
    field.dispatchEvent(new Event('input'));
    await navigation;
    await completeProducts(harness);
    expect(queryParams()).toEqual({ name: 'Teclado', category: 'Periféricos' });
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ replaceUrl: true }));
  });

  it('atualiza categoria na URL preservando nome', async () => {
    const { harness, element } = await render('/admin/products?name=Teclado');
    const select = element.querySelector('select');
    if (!select) throw new Error('Seletor de categoria ausente');
    const navigation = nextNavigation();
    select.value = 'Periféricos';
    select.dispatchEvent(new Event('change'));
    await navigation;
    await completeProducts(harness);
    expect(queryParams()).toEqual({ name: 'Teclado', category: 'Periféricos' });
  });

  it('remove nome e categoria da URL ao limpar', async () => {
    const { harness, element } = await render(
      '/admin/products?name=Teclado&category=Perif%C3%A9ricos&extra=1',
    );
    const navigation = nextNavigation();
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'LIMPAR FILTRO')
      ?.click();
    await navigation;
    await completeProducts(harness);
    await harness.fixture.whenStable();
    expect(queryParams()).toEqual({ extra: '1' });
    expect(element.querySelector('input')?.value).toBe('');
    expect(element.querySelector('select')?.value).toBe('');
  });

  it('mantém a busca por nome disponível quando categorias falham', async () => {
    const { element } = await render('/admin/products', true);
    expect(element.querySelector('select')?.disabled).toBe(true);
    expect(element.querySelectorAll('option')).toHaveLength(1);
    expect(element.querySelector('input')?.disabled).toBe(false);
  });

  it('ignora parâmetros repetidos sem quebrar a busca', async () => {
    const { element } = await render('/admin/products?name=A&name=B&category=A&category=B');
    expect(element.querySelector('input')?.value).toBe('');
    expect(element.querySelector('select')?.value).toBe('');
  });

  it('mostra quatro skeletons apenas na primeira carga e solicita quatro produtos', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    expect(element.querySelectorAll('p-skeleton')).toHaveLength(4);
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelectorAll('ul[aria-label="Produtos"] > li')).toHaveLength(4);
    expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain(
      '4 produtos encontrados.',
    );
  });

  it('envia os filtros da URL e sempre solicita a página zero', async () => {
    const { harness } = await render(
      '/admin/products?page=3&name=Teclado&category=Perif%C3%A9ricos',
      false,
      false,
    );
    http
      .expectOne('/api/product?page=0&size=4&name=Teclado&category=Perif%C3%A9ricos')
      .flush(productPage);
    await settle(harness);
  });

  it('preserva os mesmos cards com opacidade reduzida enquanto troca os filtros', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    const cards = Array.from(element.querySelectorAll('article'));
    await harness.navigateByUrl('/admin/products?name=Monitor&category=Monitores');
    TestBed.tick();
    harness.detectChanges();
    expect(element.querySelectorAll('article')).toHaveLength(4);
    Array.from(element.querySelectorAll('article')).forEach((card, index) =>
      expect(card).toBe(cards[index]),
    );
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    expect(element.querySelector('ul[aria-label="Produtos"]')?.classList).toContain('opacity-50');
    http.expectOne('/api/product?page=0&size=4&name=Monitor&category=Monitores').flush({
      ...productPage,
      content: [{ ...products[0], id: 'monitor', name: 'Monitor' }],
      totalElements: 1,
    });
    await settle(harness);
    expect(element.querySelectorAll('article')).toHaveLength(1);
    expect(element.querySelector('article')?.textContent).toContain('Monitor');
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('false');
    expect(element.querySelector('ul[aria-label="Produtos"]')?.classList).not.toContain(
      'opacity-50',
    );
    expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain(
      '1 produto encontrado.',
    );
  });

  it('mantém a última página bem-sucedida ao tentar novamente após falha no filtro', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    await harness.navigateByUrl('/admin/products?name=Monitor');
    TestBed.tick();
    http
      .expectOne('/api/product?page=0&size=4&name=Monitor')
      .flush({ detail: 'Falha temporária.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Falha temporária.');
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Tentar novamente')
      ?.click();
    TestBed.tick();
    harness.detectChanges();
    expect(element.querySelectorAll('article')).toHaveLength(4);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    http.expectOne('/api/product?page=0&size=4&name=Monitor').flush(emptyPage);
    await settle(harness);
    expect(element.querySelectorAll('article')).toHaveLength(0);
    expect(element.textContent).toContain('Nenhum produto encontrado.');
  });

  it('mostra estado vazio sem ação adicional quando não há filtros', async () => {
    const { element } = await render();
    expect(element.textContent).toContain('Nenhum produto encontrado.');
    expect(
      Array.from(element.querySelectorAll('button')).some(
        (button) => button.textContent?.trim() === 'Limpar filtros',
      ),
    ).toBe(false);
  });

  it('limpa os filtros pela ação do estado vazio', async () => {
    const { harness, element } = await render('/admin/products?name=Teclado');
    expect(element.textContent).toContain('Nenhum produto encontrado.');
    const clear = Array.from(element.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Limpar filtros',
    );
    expect(clear).toBeDefined();
    const navigation = nextNavigation();
    clear?.click();
    await navigation;
    await completeProducts(harness);
    expect(TestBed.inject(Router).url).toBe('/admin/products');
    expect(element.querySelector('input')?.value).toBe('');
  });

  it.each([
    [{ detail: 'Serviço indisponível.' }, 'Serviço indisponível.'],
    [{}, 'Não foi possível carregar os produtos. Tente novamente.'],
  ])('mostra erro e permite recarregar sem voltar aos skeletons', async (problem, message) => {
    const { harness, element } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush(problem, {
      status: 503,
      statusText: 'Unavailable',
    });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain(message);
    const retry = Array.from(element.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Tentar novamente',
    );
    expect(retry).toBeDefined();
    retry?.click();
    TestBed.tick();
    harness.detectChanges();
    expect(element.querySelector('p-skeleton')).toBeNull();
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    expect(element.querySelector('p-message')).toBeNull();
    expect(element.querySelectorAll('article')).toHaveLength(4);
  });
});
