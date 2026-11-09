import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Confirmation, ConfirmationService, MessageService } from 'primeng/api';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NavigationEnd, provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, firstValueFrom } from 'rxjs';
import { ProductSearch } from '../../../shared/ui/product-search/product-search';
import { Pagination } from '../../../shared/ui/pagination/pagination';
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
        ConfirmationService,
        MessageService,
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
    await Promise.resolve();
    TestBed.tick();
    for (const request of http.match((req) => req.url === '/api/inventory')) {
      request.flush([]);
    }
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

  it('ADICIONAR é um link que preserva os filtros e a página', async () => {
    const { element } = await render('/admin/products?name=Teclado&extra=1&page=2');
    const link = element.querySelector('a');
    expect(link?.textContent?.trim()).toBe('ADICIONAR');
    expect(link?.getAttribute('href')).toBe('/admin/products/new?name=Teclado&extra=1&page=2');
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
    const { harness, element } = await render('/admin/products?page=3&category=Perif%C3%A9ricos');
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
    const { harness, element } = await render('/admin/products?page=3&name=Teclado');
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
      '/admin/products?page=3&name=Teclado&category=Perif%C3%A9ricos&extra=1',
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

  it('descarta a rolagem pendente quando a paginação falha', async () => {
    const { harness, element } = await render(
      '/admin/products?name=Teclado&category=Perif%C3%A9ricos',
      false,
      false,
    );
    http.expectOne('/api/product?page=0&size=4&name=Teclado&category=Perif%C3%A9ricos').flush({
      ...emptyPage,
      content: products,
      totalElements: 12,
      totalPages: 3,
    });
    await settle(harness);
    const results = element.querySelector<HTMLElement>('[aria-busy]');
    if (!results) throw new Error('Região de resultados ausente');
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    element.querySelector<HTMLButtonElement>('[aria-label="Página 2"]')?.click();
    await navigate.mock.results[0]?.value;
    TestBed.tick();
    http
      .expectOne('/api/product?page=1&size=4&name=Teclado&category=Perif%C3%A9ricos')
      .flush({ detail: 'Falha ao paginar.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Falha ao paginar.');
    expect(scroll).not.toHaveBeenCalled();
    await harness.navigateByUrl('/admin/products?name=Monitor');
    TestBed.tick();
    http.expectOne('/api/product?page=0&size=4&name=Monitor').flush({
      ...emptyPage,
      content: products,
      totalElements: 1,
      totalPages: 1,
    });
    await settle(harness);
    expect(element.querySelectorAll('article').length).toBeGreaterThan(0);
    expect(scroll).not.toHaveBeenCalled();
  });

  it.each([
    ['nameChange', 'Monitor', 'name=Monitor&category=Perif%C3%A9ricos'],
    ['categoryChange', 'Monitores', 'name=Teclado&category=Monitores'],
    ['clear', undefined, ''],
  ])(
    'descarta a rolagem pendente ao receber %s durante a paginação',
    async (event, value, query) => {
      const { harness, element } = await render(
        '/admin/products?name=Teclado&category=Perif%C3%A9ricos',
        false,
        false,
      );
      http.expectOne('/api/product?page=0&size=4&name=Teclado&category=Perif%C3%A9ricos').flush({
        ...emptyPage,
        content: products,
        totalElements: 12,
        totalPages: 3,
      });
      await settle(harness);
      const results = element.querySelector<HTMLElement>('[aria-busy]');
      if (!results) throw new Error('Região de resultados ausente');
      const scroll = vi.fn();
      results.scrollIntoView = scroll;
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
      element.querySelector<HTMLButtonElement>('[aria-label="Página 2"]')?.click();
      await navigate.mock.results[0]?.value;
      TestBed.tick();
      const pending = http.expectOne(
        '/api/product?page=1&size=4&name=Teclado&category=Perif%C3%A9ricos',
      );
      const search = harness.routeDebugElement?.query(By.directive(ProductSearch));
      if (!search) throw new Error('Busca de produtos ausente');
      const filterNavigation = nextNavigation();
      search.triggerEventHandler(event, value);
      await filterNavigation;
      TestBed.tick();
      expect(pending.cancelled).toBe(true);
      http.expectOne('/api/product?page=0&size=4' + (query ? '&' + query : '')).flush({
        ...emptyPage,
        content: products,
        totalElements: 1,
        totalPages: 1,
      });
      await settle(harness);
      expect(element.querySelectorAll('article').length).toBeGreaterThan(0);
      expect(scroll).not.toHaveBeenCalled();
    },
  );

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

  it('envia os filtros e a página da URL mantendo quatro produtos por página', async () => {
    const { harness } = await render(
      '/admin/products?page=3&name=Teclado&category=Perif%C3%A9ricos',
      false,
      false,
    );
    http
      .expectOne('/api/product?page=2&size=4&name=Teclado&category=Perif%C3%A9ricos')
      .flush({ ...productPage, page: 2, totalElements: 12, totalPages: 3 });
    await settle(harness);
  });

  it.each(['', '0', '-1', 'abc', '1.5', '1e2', '2147483648', '999999999999999999999', '1&page=2'])(
    'normaliza a página inválida ou repetida %s para a primeira',
    async (page) => {
      const { harness } = await render('/admin/products?page=' + page, false, false);
      http.expectOne('/api/product?page=0&size=4').flush(emptyPage);
      await settle(harness);
    },
  );

  it.each([
    ['0002', '1'],
    ['2147483647', '2147483646'],
  ])('converte a página válida %s para o índice %s da API', async (page, index) => {
    const { harness } = await render('/admin/products?page=' + page, false, false);
    http.expectOne('/api/product?page=' + index + '&size=4').flush(emptyPage);
    await settle(harness);
  });

  it.each([0, 1, 3])(
    'mostra paginação somente quando há mais de uma página: %s',
    async (totalPages) => {
      const { harness, element } = await render('/admin/products', false, false);
      http.expectOne('/api/product?page=0&size=4').flush({
        ...productPage,
        content: totalPages ? products : [],
        totalElements: totalPages * 4,
        totalPages,
      });
      await settle(harness);
      expect(element.querySelectorAll('app-pagination')).toHaveLength(totalPages > 1 ? 1 : 0);
      expect(element.querySelectorAll('nav[aria-label="Paginação"]')).toHaveLength(
        totalPages > 1 ? 1 : 0,
      );
    },
  );

  it('pagina preservando filtros e cards durante a carga e rola após renderizar os resultados', async () => {
    const { harness, element } = await render('/admin/products?name=Teclado&extra=1', false, false);
    http.expectOne('/api/product?page=0&size=4&name=Teclado').flush({
      ...productPage,
      totalElements: 12,
      totalPages: 3,
    });
    await settle(harness);
    const results = element.querySelector<HTMLElement>('[aria-busy]');
    if (!results) throw new Error('Região de resultados ausente');
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const cards = Array.from(element.querySelectorAll('article'));
    const navigation = nextNavigation();
    element.querySelector<HTMLButtonElement>('[aria-label="Página 2"]')?.click();
    await navigation;
    TestBed.tick();
    harness.detectChanges();
    expect(queryParams()).toEqual({ name: 'Teclado', extra: '1', page: '2' });
    expect(Array.from(element.querySelectorAll('article'))).toEqual(cards);
    expect(element.querySelector('ul[aria-label="Produtos"]')?.classList).toContain('opacity-50');
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-live]')?.textContent).toContain('Carregando produtos.');
    expect(scroll).not.toHaveBeenCalled();
    expect.soft(element.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('1');
    expect
      .soft(element.querySelector('app-pagination')?.parentElement?.classList)
      .toContain('opacity-50');
    http.expectOne('/api/product?page=1&size=4&name=Teclado').flush({
      ...productPage,
      page: 1,
      content: [{ ...products[0], id: 'page-2', name: 'Teclado da segunda página' }],
      totalElements: 5,
      totalPages: 2,
    });
    await settle(harness);
    expect(element.querySelectorAll('article')).toHaveLength(1);
    expect(element.querySelector('article')?.textContent).toContain('Teclado da segunda página');
    expect(element.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('2');
    expect(element.querySelector('app-pagination')?.parentElement?.classList).not.toContain(
      'opacity-50',
    );
    expect(scroll).toHaveBeenCalledWith({ block: 'start' });
  });

  it('ignora a página atual e páginas fora do intervalo', async () => {
    const { harness } = await render('/admin/products?page=2', false, false);
    http.expectOne('/api/product?page=1&size=4').flush({
      ...productPage,
      page: 1,
      totalElements: 12,
      totalPages: 3,
    });
    await settle(harness);
    const pagination = harness.routeDebugElement
      ?.query(By.directive(Pagination))
      .injector.get(Pagination);
    expect(pagination).toBeDefined();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    for (const page of [1, -1, 3]) pagination?.pageChange.emit(page);
    await settle(harness);
    expect(navigate).not.toHaveBeenCalled();
    http.expectNone((req) => req.url === '/api/product');
  });

  it('corrige uma URL além da última página preservando filtros e substituindo o histórico', async () => {
    const { harness, element } = await render(
      '/admin/products?page=999&name=Teclado&extra=1',
      false,
      false,
    );
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const navigation = nextNavigation();
    http.expectOne('/api/product?page=998&size=4&name=Teclado').flush({
      ...emptyPage,
      page: 998,
      totalElements: 9,
      totalPages: 3,
    });
    await Promise.resolve();
    TestBed.tick();
    harness.detectChanges();
    expect.soft(element.textContent).not.toContain('Nenhum produto encontrado.');
    expect.soft(element.querySelectorAll('p-skeleton')).toHaveLength(4);
    await navigation;
    TestBed.tick();
    harness.detectChanges();
    expect.soft(element.querySelectorAll('p-skeleton')).toHaveLength(4);
    expect.soft(element.textContent).not.toContain('Nenhum produto encontrado.');
    expect(queryParams()).toEqual({ page: '3', name: 'Teclado', extra: '1' });
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({
        queryParams: { page: 3 },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      }),
    );
    http.expectOne('/api/product?page=2&size=4&name=Teclado').flush({
      ...productPage,
      page: 2,
      totalElements: 9,
      totalPages: 3,
    });
    await settle(harness);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelectorAll('article')).toHaveLength(4);
  });

  it('aguarda a página corrigida antes de rolar quando a paginação retorna fora do intervalo', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush({
      ...productPage,
      totalElements: 9,
      totalPages: 3,
    });
    await settle(harness);
    const results = element.querySelector<HTMLElement>('[aria-busy]');
    if (!results) throw new Error('Região de resultados ausente');
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    element.querySelector<HTMLButtonElement>('[aria-label="Página 3"]')?.click();
    await navigate.mock.results[0]?.value;
    TestBed.tick();
    const correction = nextNavigation();
    http.expectOne('/api/product?page=2&size=4').flush({
      ...emptyPage,
      page: 2,
      totalElements: 8,
      totalPages: 2,
    });
    await Promise.resolve();
    TestBed.tick();
    harness.detectChanges();
    expect.soft(scroll).not.toHaveBeenCalled();
    await correction;
    TestBed.tick();
    harness.detectChanges();
    expect.soft(scroll).not.toHaveBeenCalled();
    expect.soft(element.textContent).not.toContain('Nenhum produto encontrado.');
    http.expectOne('/api/product?page=1&size=4').flush({
      ...productPage,
      page: 1,
      totalElements: 8,
      totalPages: 2,
    });
    await settle(harness);
    expect(scroll).toHaveBeenCalledExactlyOnceWith({ block: 'start' });
    expect(element.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('2');
  });

  it('aguarda os novos resultados antes de corrigir uma página usando o total anterior', async () => {
    const { harness } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    await harness.navigateByUrl('/admin/products?page=3&category=Monitores');
    TestBed.tick();
    expect(queryParams()).toEqual({ page: '3', category: 'Monitores' });
    http.expectOne('/api/product?page=2&size=4&category=Monitores').flush({
      ...productPage,
      page: 2,
      totalElements: 12,
      totalPages: 3,
    });
    await settle(harness);
    expect(queryParams()).toEqual({ page: '3', category: 'Monitores' });
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
  async function renderActions() {
    const result = await render('/admin/products?name=Teclado&extra=1', false, false);
    http.expectOne((req) => req.url === '/api/product').flush(productPage);
    await settle(result.harness);
    return result;
  }

  it('EDITAR navega para edição preservando query params', async () => {
    const { element } = await renderActions();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    element.querySelector<HTMLButtonElement>('article button')?.click();
    expect(navigate).toHaveBeenCalledWith(['/admin/products', '0', 'edit'], {
      queryParamsHandling: 'preserve',
    });
  });

  it('abre confirmação com nome e foco no cancelar; cancelar não exclui', async () => {
    const { element } = await renderActions();
    const confirm = vi.spyOn(TestBed.inject(ConfirmationService), 'confirm');
    element.querySelectorAll<HTMLButtonElement>('article button')[1]?.click();
    const confirmation = confirm.mock.calls[0]?.[0];
    expect(confirmation?.message).toContain('Excluir "Produto 0"?');
    expect(confirmation?.defaultFocus).toBe('reject');
    expect(confirmation?.acceptLabel).toBe('Excluir');
    expect(confirmation?.rejectLabel).toBe('Cancelar');
    confirmation?.reject?.();
    http.expectNone((req) => req.method === 'DELETE');
  });

  it('exclui com ETag, bloqueia confirmação duplicada e recarrega sem skeleton', async () => {
    const { harness, element } = await renderActions();
    const messages = vi.spyOn(TestBed.inject(MessageService), 'add');
    const confirm = vi.spyOn(TestBed.inject(ConfirmationService), 'confirm');
    const remove = element.querySelectorAll<HTMLButtonElement>('article button')[1];
    remove?.click();
    remove?.click();
    expect(confirm).toHaveBeenCalledTimes(1);
    const confirmation: Confirmation | undefined = confirm.mock.calls[0]?.[0];
    confirmation?.accept?.();
    confirmation?.accept?.();
    const req = http.expectOne('/api/product/0');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.headers.get('If-Match')).toBe('"0"');
    req.flush(null, { status: 204, statusText: 'No Content' });
    TestBed.tick();
    harness.detectChanges();
    expect(element.querySelectorAll('article')).toHaveLength(4);
    expect(element.querySelector('p-skeleton')).toBeNull();
    http.expectOne((req) => req.url === '/api/product').flush(emptyPage);
    await settle(harness);
    expect(messages).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success' }));
    expect(element.textContent).toContain('Nenhum produto encontrado.');
  });

  it.each([1, 2])(
    'após excluir na página 2, mantém ou corrige a página conforme o total %s',
    async (totalPages) => {
      const { harness, element } = await render('/admin/products?page=2&extra=1', false, false);
      http.expectOne('/api/product?page=1&size=4').flush({
        ...productPage,
        page: 1,
        content: totalPages === 1 ? [products[0]] : products.slice(0, 2),
        totalElements: totalPages === 1 ? 5 : 6,
        totalPages: 2,
      });
      await settle(harness);
      const confirm = vi.spyOn(TestBed.inject(ConfirmationService), 'confirm');
      element.querySelectorAll<HTMLButtonElement>('article button')[1]?.click();
      confirm.mock.calls[0]?.[0].accept?.();
      http.expectOne('/api/product/0').flush(null, { status: 204, statusText: 'No Content' });
      TestBed.tick();
      expect(queryParams()).toEqual({ page: '2', extra: '1' });
      const navigation = totalPages === 1 ? nextNavigation() : undefined;
      http.expectOne('/api/product?page=1&size=4').flush({
        ...productPage,
        page: 1,
        content: totalPages === 1 ? [] : [products[1]],
        totalElements: totalPages === 1 ? 4 : 5,
        totalPages,
      });
      await Promise.resolve();
      TestBed.tick();
      harness.detectChanges();
      expect.soft(element.textContent).not.toContain('Nenhum produto encontrado.');
      if (navigation) {
        await navigation;
        TestBed.tick();
        harness.detectChanges();
        expect.soft(element.textContent).not.toContain('Nenhum produto encontrado.');
        expect.soft(element.querySelectorAll('article')).toHaveLength(1);
        http.expectOne('/api/product?page=0&size=4').flush(productPage);
      }
      await settle(harness);
      expect(queryParams()).toEqual({ page: String(totalPages), extra: '1' });
      expect(element.querySelectorAll('article')).toHaveLength(totalPages === 1 ? 4 : 1);
    },
  );

  it.each([
    [412, 'alterado'],
    [404, 'não existe'],
    [503, 'Falha ao excluir.'],
  ])('trata erro %s ao excluir', async (status, message) => {
    const { harness, element } = await renderActions();
    const messages = vi.spyOn(TestBed.inject(MessageService), 'add');
    const confirm = vi.spyOn(TestBed.inject(ConfirmationService), 'confirm');
    element.querySelectorAll<HTMLButtonElement>('article button')[1]?.click();
    confirm.mock.calls[0]?.[0].accept?.();
    http
      .expectOne('/api/product/0')
      .flush({ detail: 'Falha ao excluir.' }, { status, statusText: 'Error' });
    TestBed.tick();
    if (status === 503) http.expectNone((req) => req.url === '/api/product');
    else http.expectOne((req) => req.url === '/api/product').flush(productPage);
    await settle(harness);
    expect(messages).toHaveBeenCalledWith(
      expect.objectContaining({ detail: expect.stringContaining(message) }),
    );
  });
  it('renderiza o nome da confirmação literalmente, sem criar elementos HTML', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    const name = '<img src=x onerror=alert(1)>Produto';
    http
      .expectOne((req) => req.url === '/api/product')
      .flush({ ...productPage, content: [{ ...products[0], name }] });
    await settle(harness);
    element.querySelectorAll<HTMLButtonElement>('article button')[1]?.click();
    await settle(harness);
    const dialog = document.querySelector('div[role="alertdialog"]');
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain(name);
    expect(dialog?.querySelector('img')).toBeNull();
    const cancel = Array.from(dialog?.querySelectorAll('button') ?? []).find(
      (button) => button.textContent?.trim() === 'Cancelar',
    );
    cancel?.click();
    await settle(harness);
    http.expectNone((req) => req.method === 'DELETE');
  });
  it('consulta estoque em lote por página e distingue ausência de estoque indisponível', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    http
      .expectOne('/api/product?page=0&size=4')
      .flush({ ...productPage, totalPages: 2, totalElements: 5 });
    await Promise.resolve();
    TestBed.tick();
    const request = http.expectOne('/api/inventory?skuCode=0&skuCode=1&skuCode=2&skuCode=3');
    expect(request.request.method).toBe('GET');
    request.flush([{ skuCode: '0', quantity: 15, inStock: true }]);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(element.querySelectorAll('article')[0]?.textContent).toContain('Estoque: 15');
    expect(element.querySelectorAll('article')[1]?.textContent).toContain('Estoque: 0');
    await harness.navigateByUrl('/admin/products?page=2');
    TestBed.tick();
    http.expectOne('/api/product?page=1&size=4').flush({
      ...productPage,
      content: [{ ...products[0], id: '4' }],
      page: 1,
      totalPages: 2,
      totalElements: 5,
    });
    await Promise.resolve();
    TestBed.tick();
    http
      .expectOne('/api/inventory?skuCode=4')
      .flush(
        { detail: 'Consulta de estoque indisponível.' },
        { status: 503, statusText: 'Unavailable' },
      );
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(element.textContent).toContain('Consulta de estoque indisponível.');
    expect(element.querySelector('article')?.textContent).not.toContain('Estoque: 0');
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Recarregar estoque')
      ?.click();
    TestBed.tick();
    http
      .expectOne('/api/inventory?skuCode=4')
      .flush([{ skuCode: '4', quantity: 7, inStock: true }]);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(element.querySelector('article')?.textContent).toContain('Estoque: 7');
    expect(element.textContent).not.toContain('Consulta de estoque indisponível.');
  });
});
