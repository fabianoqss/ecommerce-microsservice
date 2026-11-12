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
    name: 'TKL Mechanical Keyboard',
    description: 'Keyboard',
    price: 89.9,
    category: 'Peripherals',
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
      throw new Error('The catalog route was not rendered');
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

  it('shows eight skeletons while loading', async () => {
    const { harness, element } = await render();
    expect(element.querySelectorAll('p-skeleton')).toHaveLength(8);
    expect(element.querySelector('h1')?.textContent?.trim()).toBe('Product catalog');
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
  });

  it('shows products in a list and announces the result count', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush(['Peripherals']);
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
    expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain('1 product found');
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('nav[aria-label="Pagination"]')).toBeNull();
  });

  it('keeps the catalog working when categories fail', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush({}, { status: 404, statusText: 'Not Found' });
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
    expect(element.querySelector('select')?.disabled).toBe(true);
    expect(element.textContent).toContain('No products found.');
    expect(element.querySelector('input')?.disabled).toBe(false);
  });

  it('clears filters and page using the empty state action', async () => {
    const { harness, element } = await render('/catalog?page=3&name=Keyboard&category=Peripherals');
    http.expectOne('/api/product/categories').flush(['Peripherals']);
    http
      .expectOne((req) => req.url === '/api/product' && req.params.get('page') === '2')
      .flush(emptyPage);
    await settle(harness);
    const clear = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Clear filters'),
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
    [{ detail: 'Product service unavailable.' }, 'Product service unavailable.'],
    [{}, 'Unable to load products. Please try again.'],
  ])('shows an error and allows retrying', async (problem, message) => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush([]);
    http
      .expectOne('/api/product?page=0&size=8')
      .flush(problem, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain(message);
    const retry = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Try again'),
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
    'normalizes invalid page %s to the first page',
    async (page) => {
      const { harness } = await render('/catalog?page=' + page);
      http.expectOne('/api/product/categories').flush([]);
      http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
      await settle(harness);
    },
  );

  it('applies a trimmed name to the URL without creating a history entry and removes the page', async () => {
    const { harness, element } = await render('/catalog?page=3&category=Peripherals');
    http.expectOne('/api/product/categories').flush(['Peripherals']);
    http.expectOne((req) => req.url === '/api/product').flush(emptyPage);
    await settle(harness);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const field = element.querySelector('input');
    if (!field) {
      throw new Error('Name field missing');
    }
    const navigation = nextNavigation();
    field.value = ' Keyboard ';
    field.dispatchEvent(new Event('input'));
    await navigation;
    TestBed.tick();
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ replaceUrl: true }));
    expect(TestBed.inject(Router).parseUrl(TestBed.inject(Router).url).queryParams).toEqual({
      name: 'Keyboard',
      category: 'Peripherals',
    });
    const request = http.expectOne((req) => req.url === '/api/product');
    expect(request.request.params.get('name')).toBe('Keyboard');
    expect(request.request.params.get('page')).toBe('0');
    request.flush(emptyPage);
    await settle(harness);
  });

  it('changes category while preserving the name and removes the page', async () => {
    const { harness, element } = await render('/catalog?page=3&name=Keyboard');
    http.expectOne('/api/product/categories').flush(['Peripherals']);
    http.expectOne((req) => req.url === '/api/product').flush(emptyPage);
    await settle(harness);
    const select = element.querySelector('select');
    if (!select) {
      throw new Error('Category selector missing');
    }
    const navigation = nextNavigation();
    select.value = 'Peripherals';
    select.dispatchEvent(new Event('change'));
    await navigation;
    TestBed.tick();
    expect(TestBed.inject(Router).parseUrl(TestBed.inject(Router).url).queryParams).toEqual({
      name: 'Keyboard',
      category: 'Peripherals',
    });
    const request = http.expectOne((req) => req.url === '/api/product');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('category')).toBe('Peripherals');
    request.flush(emptyPage);
    await settle(harness);
  });

  it('updates the URL on pagination and scrolls to the top of the list', async () => {
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
      throw new Error('Results region missing');
    }
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const navigation = nextNavigation();
    element.querySelector<HTMLButtonElement>('[aria-label="Page 2"]')?.click();
    await navigation;
    TestBed.tick();
    expect(TestBed.inject(Router).url).toBe('/catalog?page=2');
    http.expectOne('/api/product?page=1&size=8').flush(emptyPage);
    await settle(harness);
    expect(scroll).toHaveBeenCalledWith({ block: 'start' });
  });

  it('discards pending scrolling when pagination fails', async () => {
    const { harness, element } = await render('/catalog?name=Keyboard&category=Peripherals');
    http.expectOne('/api/product/categories').flush(['Peripherals', 'Monitors']);
    http.expectOne('/api/product?page=0&size=8&name=Keyboard&category=Peripherals').flush({
      ...emptyPage,
      content: [product],
      totalElements: 24,
      totalPages: 3,
    });
    await settle(harness);
    const results = element.querySelector<HTMLElement>('[aria-busy]');
    if (!results) throw new Error('Results region missing');
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    element.querySelector<HTMLButtonElement>('[aria-label="Page 2"]')?.click();
    await navigate.mock.results[0]?.value;
    TestBed.tick();
    http
      .expectOne('/api/product?page=1&size=8&name=Keyboard&category=Peripherals')
      .flush({ detail: 'Pagination failed.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Pagination failed.');
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
    ['nameChange', 'Monitor', 'name=Monitor&category=Peripherals'],
    ['categoryChange', 'Monitors', 'name=Keyboard&category=Monitors'],
    ['clear', undefined, ''],
  ])('discards pending scrolling on %s during pagination', async (event, value, query) => {
    const { harness, element } = await render('/catalog?name=Keyboard&category=Peripherals');
    http.expectOne('/api/product/categories').flush(['Peripherals', 'Monitors']);
    http.expectOne('/api/product?page=0&size=8&name=Keyboard&category=Peripherals').flush({
      ...emptyPage,
      content: [product],
      totalElements: 24,
      totalPages: 3,
    });
    await settle(harness);
    const results = element.querySelector<HTMLElement>('[aria-busy]');
    if (!results) throw new Error('Results region missing');
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    element.querySelector<HTMLButtonElement>('[aria-label="Page 2"]')?.click();
    await navigate.mock.results[0]?.value;
    TestBed.tick();
    const pending = http.expectOne('/api/product?page=1&size=8&name=Keyboard&category=Peripherals');
    const search = harness.routeDebugElement?.query(By.directive(ProductSearch));
    if (!search) throw new Error('Product search missing');
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
  });

  it('ignores repeated parameters without breaking the page', async () => {
    const { harness } = await render(
      '/catalog?name=Keyboard&name=Monitor&category=A&category=B&page=1&page=2',
    );
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
  });

  it('recovers a page beyond the total while preserving filters', async () => {
    const { harness } = await render('/catalog?page=999&name=Keyboard');
    http.expectOne('/api/product/categories').flush([]);
    const navigation = nextNavigation();
    http.expectOne('/api/product?page=998&size=8&name=Keyboard').flush({
      ...emptyPage,
      page: 998,
      totalElements: 20,
      totalPages: 3,
    });
    await Promise.resolve();
    TestBed.tick();
    await navigation;
    TestBed.tick();
    expect(TestBed.inject(Router).url).toBe('/catalog?page=3&name=Keyboard');
    http.expectOne('/api/product?page=2&size=8&name=Keyboard').flush({
      ...emptyPage,
      page: 2,
      content: [product],
      totalElements: 20,
      totalPages: 3,
    });
    await settle(harness);
  });

  it('cancels the pending name when clearing the empty state even without URL filters', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product?page=0&size=8').flush(emptyPage);
    await settle(harness);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    if (!field) {
      throw new Error('Name field missing');
    }
    field.value = 'Keyboard';
    field.dispatchEvent(new Event('input'));
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Clear filters')
      ?.click();
    await vi.advanceTimersByTimeAsync(300);
    expect(field.value).toBe('');
    expect(TestBed.inject(Router).url).toBe('/catalog');
    http.expectNone((request) => request.url === '/api/product');
  });
});
