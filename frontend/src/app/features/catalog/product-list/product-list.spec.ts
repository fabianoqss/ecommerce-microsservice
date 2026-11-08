import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NavigationEnd, provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, firstValueFrom } from 'rxjs';
import { ProductSearch } from '../../../shared/ui/product-search/product-search';
import { ProductList } from './product-list';

describe('ProductList', () => {
  let http: HttpTestingController;
  const emptyPage = { content: [], page: 0, size: 8, totalElements: 0, totalPages: 0 };
  const product = {
    id: '1',
    name: 'Teclado Mecânico TKL',
    description: 'Teclado',
    price: 89.9,
    category: 'Periféricos',
    imageUrl: null,
    version: 0,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'catalog', component: ProductList }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  async function render(url = '/catalog') {
    const harness = await RouterTestingHarness.create(url);
    TestBed.tick();
    const element = harness.routeNativeElement;
    if (!element) {
      throw new Error('A rota do catálogo não foi renderizada');
    }
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function nextNavigation() {
    return firstValueFrom(
      TestBed.inject(Router).events.pipe(filter((event) => event instanceof NavigationEnd)),
    );
  }

  it('mostra oito skeletons enquanto carrega', async () => {
    const { harness, element } = await render();
    expect(element.querySelectorAll('p-skeleton')).toHaveLength(8);
    expect(element.querySelector('h1')?.textContent?.trim()).toBe('Catálogo de produtos');
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
  });

  it('mostra produtos em lista e anuncia o total de resultados', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush(['Periféricos']);
    http.expectOne('/api/product?page=0&size=8').flush({
      ...emptyPage,
      content: [product],
      totalElements: 1,
      totalPages: 1,
    });
    await settle(harness);
    expect(element.querySelectorAll('ul li article')).toHaveLength(1);
    expect(element.textContent).toContain(product.name);
    expect(element.querySelector('a[href="/catalog/1"] article')).not.toBeNull();
    expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain(
      '1 produto encontrado',
    );
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('nav[aria-label="Paginação"]')).toBeNull();
  });

  it('mantém o catálogo funcionando quando categorias falham', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush({}, { status: 404, statusText: 'Not Found' });
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
    expect(element.querySelector('select')?.disabled).toBe(true);
    expect(element.textContent).toContain('Nenhum produto encontrado.');
    expect(element.querySelector('input')?.disabled).toBe(false);
  });

  it('limpa os filtros e a página pela ação do estado vazio', async () => {
    const { harness, element } = await render(
      '/catalog?page=3&name=Teclado&category=Perif%C3%A9ricos',
    );
    http.expectOne('/api/product/categories').flush(['Periféricos']);
    http
      .expectOne((req) => req.url === '/api/product' && req.params.get('page') === '2')
      .flush(emptyPage);
    await settle(harness);
    const clear = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Limpar filtros'),
    );
    expect(clear).toBeDefined();
    const navigation = nextNavigation();
    clear?.click();
    await navigation;
    TestBed.tick();
    expect(TestBed.inject(Router).url).toBe('/catalog');
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
  });

  it.each([
    [{ detail: 'Serviço de produtos indisponível.' }, 'Serviço de produtos indisponível.'],
    [{}, 'Não foi possível carregar os produtos. Tente novamente.'],
  ])('mostra erro e permite tentar novamente', async (problem, message) => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush([]);
    http
      .expectOne('/api/product?page=0&size=8')
      .flush(problem, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain(message);
    const retry = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Tentar novamente'),
    );
    retry?.click();
    TestBed.tick();
    http
      .expectOne('/api/product?page=0&size=8')
      .flush({ ...emptyPage, content: [product], totalElements: 1, totalPages: 1 });
    await settle(harness);
    expect(element.querySelector('p-message')).toBeNull();
    expect(element.querySelectorAll('article')).toHaveLength(1);
  });

  it.each(['0', '-1', 'abc', '1.5', '999999999999999999999'])(
    'normaliza página inválida %s para a primeira',
    async (page) => {
      const { harness } = await render('/catalog?page=' + page);
      http.expectOne('/api/product/categories').flush([]);
      http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
      await settle(harness);
    },
  );

  it('aplica nome aparado na URL sem criar entrada no histórico e remove a página', async () => {
    const { harness, element } = await render('/catalog?page=3&category=Perif%C3%A9ricos');
    http.expectOne('/api/product/categories').flush(['Periféricos']);
    http.expectOne((req) => req.url === '/api/product').flush(emptyPage);
    await settle(harness);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const field = element.querySelector('input');
    if (!field) {
      throw new Error('Campo de nome ausente');
    }
    const navigation = nextNavigation();
    field.value = ' Teclado ';
    field.dispatchEvent(new Event('input'));
    await navigation;
    TestBed.tick();
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ replaceUrl: true }));
    expect(TestBed.inject(Router).parseUrl(TestBed.inject(Router).url).queryParams).toEqual({
      name: 'Teclado',
      category: 'Periféricos',
    });
    const request = http.expectOne((req) => req.url === '/api/product');
    expect(request.request.params.get('name')).toBe('Teclado');
    expect(request.request.params.get('page')).toBe('0');
    request.flush(emptyPage);
    await settle(harness);
  });

  it('muda categoria preservando nome e remove a página', async () => {
    const { harness, element } = await render('/catalog?page=3&name=Teclado');
    http.expectOne('/api/product/categories').flush(['Periféricos']);
    http.expectOne((req) => req.url === '/api/product').flush(emptyPage);
    await settle(harness);
    const select = element.querySelector('select');
    if (!select) {
      throw new Error('Seletor de categoria ausente');
    }
    const navigation = nextNavigation();
    select.value = 'Periféricos';
    select.dispatchEvent(new Event('change'));
    await navigation;
    TestBed.tick();
    expect(TestBed.inject(Router).parseUrl(TestBed.inject(Router).url).queryParams).toEqual({
      name: 'Teclado',
      category: 'Periféricos',
    });
    const request = http.expectOne((req) => req.url === '/api/product');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('category')).toBe('Periféricos');
    request.flush(emptyPage);
    await settle(harness);
  });

  it('atualiza a URL ao paginar e rola para o início da lista', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product?page=0&size=8').flush({
      ...emptyPage,
      content: [product],
      totalElements: 20,
      totalPages: 3,
    });
    await settle(harness);
    const results = element.querySelector<HTMLElement>('[aria-busy]');
    if (!results) {
      throw new Error('Região de resultados ausente');
    }
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const navigation = nextNavigation();
    element.querySelector<HTMLButtonElement>('[aria-label="Página 2"]')?.click();
    await navigation;
    TestBed.tick();
    expect(TestBed.inject(Router).url).toBe('/catalog?page=2');
    http.expectOne('/api/product?page=1&size=8').flush(emptyPage);
    await settle(harness);
    expect(scroll).toHaveBeenCalledWith({ block: 'start' });
  });

  it('descarta a rolagem pendente quando a paginação falha', async () => {
    const { harness, element } = await render('/catalog?name=Teclado&category=Perif%C3%A9ricos');
    http.expectOne('/api/product/categories').flush(['Periféricos', 'Monitores']);
    http.expectOne('/api/product?page=0&size=8&name=Teclado&category=Perif%C3%A9ricos').flush({
      ...emptyPage,
      content: [product],
      totalElements: 24,
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
      .expectOne('/api/product?page=1&size=8&name=Teclado&category=Perif%C3%A9ricos')
      .flush({ detail: 'Falha ao paginar.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Falha ao paginar.');
    expect(scroll).not.toHaveBeenCalled();
    await harness.navigateByUrl('/catalog?name=Monitor');
    TestBed.tick();
    http.expectOne('/api/product?page=0&size=8&name=Monitor').flush({
      ...emptyPage,
      content: [product],
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
      const { harness, element } = await render('/catalog?name=Teclado&category=Perif%C3%A9ricos');
      http.expectOne('/api/product/categories').flush(['Periféricos', 'Monitores']);
      http.expectOne('/api/product?page=0&size=8&name=Teclado&category=Perif%C3%A9ricos').flush({
        ...emptyPage,
        content: [product],
        totalElements: 24,
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
        '/api/product?page=1&size=8&name=Teclado&category=Perif%C3%A9ricos',
      );
      const search = harness.routeDebugElement?.query(By.directive(ProductSearch));
      if (!search) throw new Error('Busca de produtos ausente');
      const filterNavigation = nextNavigation();
      search.triggerEventHandler(event, value);
      await filterNavigation;
      TestBed.tick();
      expect(pending.cancelled).toBe(true);
      http.expectOne('/api/product?page=0&size=8' + (query ? '&' + query : '')).flush({
        ...emptyPage,
        content: [product],
        totalElements: 1,
        totalPages: 1,
      });
      await settle(harness);
      expect(element.querySelectorAll('article').length).toBeGreaterThan(0);
      expect(scroll).not.toHaveBeenCalled();
    },
  );

  it('ignora parâmetros repetidos em vez de quebrar a página', async () => {
    const { harness } = await render(
      '/catalog?name=Teclado&name=Monitor&category=A&category=B&page=1&page=2',
    );
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
  });

  it('recupera uma página acima do total preservando os filtros', async () => {
    const { harness } = await render('/catalog?page=999&name=Teclado');
    http.expectOne('/api/product/categories').flush([]);
    const navigation = nextNavigation();
    http.expectOne('/api/product?page=998&size=8&name=Teclado').flush({
      ...emptyPage,
      page: 998,
      totalElements: 20,
      totalPages: 3,
    });
    await Promise.resolve();
    TestBed.tick();
    await navigation;
    TestBed.tick();
    expect(TestBed.inject(Router).url).toBe('/catalog?page=3&name=Teclado');
    http.expectOne('/api/product?page=2&size=8&name=Teclado').flush({
      ...emptyPage,
      page: 2,
      content: [product],
      totalElements: 20,
      totalPages: 3,
    });
    await settle(harness);
  });

  it('cancela o nome pendente ao limpar pelo estado vazio mesmo sem filtros na URL', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    if (!field) {
      throw new Error('Campo de nome ausente');
    }
    field.value = 'Teclado';
    field.dispatchEvent(new Event('input'));
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Limpar filtros')
      ?.click();
    await vi.advanceTimersByTimeAsync(300);
    expect(field.value).toBe('');
    expect(TestBed.inject(Router).url).toBe('/catalog');
    http.expectNone((request) => request.url === '/api/product');
  });
});
