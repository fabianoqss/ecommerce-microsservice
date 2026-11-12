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
    name: 'Product ' + index,
    description: 'Description',
    price: 2779,
    category: 'Peripherals',
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
      request.flush(['Peripherals', 'Monitors']);
    }
    if (loadProducts) {
      http.expectOne((req) => req.url === '/api/product').flush(emptyPage);
      await harness.fixture.whenStable();
    }
    harness.detectChanges();
    const element = harness.routeNativeElement;
    if (!element) {
      throw new Error('The products page was not rendered');
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

  it('identifies the page with a screen reader title', async () => {
    const { element } = await render();
    const heading = element.querySelector('h1');
    expect(heading?.textContent).toBe('Products');
    expect(heading?.classList.contains('sr-only')).toBe(true);
    expect(heading?.hasAttribute('aria-hidden')).toBe(false);
  });

  it('ADD is a link that preserves filters and the page', async () => {
    const { element } = await render('/admin/products?name=Keyboard&extra=1&page=2');
    const link = element.querySelector('a');
    expect(link?.textContent?.trim()).toBe('ADD');
    expect(link?.getAttribute('href')).toBe('/admin/products/new?name=Keyboard&extra=1&page=2');
  });

  it('fills search from URL filters and follows subsequent navigation', async () => {
    const { harness, element } = await render('/admin/products?name=Keyboard&category=Peripherals');
    expect(element.querySelector('input')?.value).toBe('Keyboard');
    expect(element.querySelector('select')?.value).toBe('Peripherals');
    await harness.navigateByUrl('/admin/products?name=Monitor&category=Monitors');
    await completeProducts(harness);
    await harness.fixture.whenStable();
    expect(element.querySelector('input')?.value).toBe('Monitor');
    expect(element.querySelector('select')?.value).toBe('Monitors');
  });

  it('updates the name in the URL while preserving category and replacing history', async () => {
    const { harness, element } = await render('/admin/products?page=3&category=Peripherals');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const field = element.querySelector('input');
    if (!field) throw new Error('Name field missing');
    const navigation = nextNavigation();
    field.value = ' Keyboard ';
    field.dispatchEvent(new Event('input'));
    await navigation;
    await completeProducts(harness);
    expect(queryParams()).toEqual({ name: 'Keyboard', category: 'Peripherals' });
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ replaceUrl: true }));
  });

  it('updates category in the URL while preserving the name', async () => {
    const { harness, element } = await render('/admin/products?page=3&name=Keyboard');
    const select = element.querySelector('select');
    if (!select) throw new Error('Category selector missing');
    const navigation = nextNavigation();
    select.value = 'Peripherals';
    select.dispatchEvent(new Event('change'));
    await navigation;
    await completeProducts(harness);
    expect(queryParams()).toEqual({ name: 'Keyboard', category: 'Peripherals' });
  });

  it('removes name and category from the URL when clearing', async () => {
    const { harness, element } = await render(
      '/admin/products?page=3&name=Keyboard&category=Peripherals&extra=1',
    );
    const navigation = nextNavigation();
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'CLEAR FILTERS')
      ?.click();
    await navigation;
    await completeProducts(harness);
    await harness.fixture.whenStable();
    expect(queryParams()).toEqual({ extra: '1' });
    expect(element.querySelector('input')?.value).toBe('');
    expect(element.querySelector('select')?.value).toBe('');
  });

  it('keeps name search available when categories fail', async () => {
    const { element } = await render('/admin/products', true);
    expect(element.querySelector('select')?.disabled).toBe(true);
    expect(element.querySelectorAll('option')).toHaveLength(1);
    expect(element.querySelector('input')?.disabled).toBe(false);
  });

  it('discards pending scrolling when pagination fails', async () => {
    const { harness, element } = await render(
      '/admin/products?name=Keyboard&category=Peripherals',
      false,
      false,
    );
    http.expectOne('/api/product?page=0&size=4&name=Keyboard&category=Peripherals').flush({
      ...emptyPage,
      content: products,
      totalElements: 12,
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
      .expectOne('/api/product?page=1&size=4&name=Keyboard&category=Peripherals')
      .flush({ detail: 'Pagination failed.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Pagination failed.');
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
    ['nameChange', 'Monitor', 'name=Monitor&category=Peripherals'],
    ['categoryChange', 'Monitors', 'name=Keyboard&category=Monitors'],
    ['clear', undefined, ''],
  ])('discards pending scrolling on %s during pagination', async (event, value, query) => {
    const { harness, element } = await render(
      '/admin/products?name=Keyboard&category=Peripherals',
      false,
      false,
    );
    http.expectOne('/api/product?page=0&size=4&name=Keyboard&category=Peripherals').flush({
      ...emptyPage,
      content: products,
      totalElements: 12,
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
    const pending = http.expectOne('/api/product?page=1&size=4&name=Keyboard&category=Peripherals');
    const search = harness.routeDebugElement?.query(By.directive(ProductSearch));
    if (!search) throw new Error('Product search missing');
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
  });

  it('ignores repeated parameters without breaking search', async () => {
    const { element } = await render('/admin/products?name=A&name=B&category=A&category=B');
    expect(element.querySelector('input')?.value).toBe('');
    expect(element.querySelector('select')?.value).toBe('');
  });

  it('shows four skeletons only on initial load and requests four products', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    expect(element.querySelectorAll('p-skeleton')).toHaveLength(4);
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelectorAll('ul[aria-label="Products"] > li')).toHaveLength(4);
    expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain(
      '4 products found.',
    );
  });

  it('sends URL filters and page while keeping four products per page', async () => {
    const { harness } = await render(
      '/admin/products?page=3&name=Keyboard&category=Peripherals',
      false,
      false,
    );
    http
      .expectOne('/api/product?page=2&size=4&name=Keyboard&category=Peripherals')
      .flush({ ...productPage, page: 2, totalElements: 12, totalPages: 3 });
    await settle(harness);
  });

  it.each(['', '0', '-1', 'abc', '1.5', '1e2', '2147483648', '999999999999999999999', '1&page=2'])(
    'normalizes invalid or repeated page %s to the first page',
    async (page) => {
      const { harness } = await render('/admin/products?page=' + page, false, false);
      http.expectOne('/api/product?page=0&size=4').flush(emptyPage);
      await settle(harness);
    },
  );

  it.each([
    ['0002', '1'],
    ['2147483647', '2147483646'],
  ])('converts valid page %s to API index %s', async (page, index) => {
    const { harness } = await render('/admin/products?page=' + page, false, false);
    http.expectOne('/api/product?page=' + index + '&size=4').flush(emptyPage);
    await settle(harness);
  });

  it.each([0, 1, 3])(
    'shows pagination only when there is more than one page: %s',
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
      expect(element.querySelectorAll('nav[aria-label="Pagination"]')).toHaveLength(
        totalPages > 1 ? 1 : 0,
      );
    },
  );

  it('paginates while preserving filters and cards during loading and scrolls after rendering results', async () => {
    const { harness, element } = await render(
      '/admin/products?name=Keyboard&extra=1',
      false,
      false,
    );
    http.expectOne('/api/product?page=0&size=4&name=Keyboard').flush({
      ...productPage,
      totalElements: 12,
      totalPages: 3,
    });
    await settle(harness);
    const results = element.querySelector<HTMLElement>('[aria-busy]');
    if (!results) throw new Error('Results region missing');
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const cards = Array.from(element.querySelectorAll('article'));
    const navigation = nextNavigation();
    element.querySelector<HTMLButtonElement>('[aria-label="Page 2"]')?.click();
    await navigation;
    TestBed.tick();
    harness.detectChanges();
    expect(queryParams()).toEqual({ name: 'Keyboard', extra: '1', page: '2' });
    expect(Array.from(element.querySelectorAll('article'))).toEqual(cards);
    expect(element.querySelector('ul[aria-label="Products"]')?.classList).toContain('opacity-50');
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-live]')?.textContent).toContain('Loading products.');
    expect(scroll).not.toHaveBeenCalled();
    expect.soft(element.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('1');
    expect
      .soft(element.querySelector('app-pagination')?.parentElement?.classList)
      .toContain('opacity-50');
    http.expectOne('/api/product?page=1&size=4&name=Keyboard').flush({
      ...productPage,
      page: 1,
      content: [{ ...products[0], id: 'page-2', name: 'Second page keyboard' }],
      totalElements: 5,
      totalPages: 2,
    });
    await settle(harness);
    expect(element.querySelectorAll('article')).toHaveLength(1);
    expect(element.querySelector('article')?.textContent).toContain('Second page keyboard');
    expect(element.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('2');
    expect(element.querySelector('app-pagination')?.parentElement?.classList).not.toContain(
      'opacity-50',
    );
    expect(scroll).toHaveBeenCalledWith({ block: 'start' });
  });

  it('ignores the current page and out-of-range pages', async () => {
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

  it('corrects a URL beyond the last page while preserving filters and replacing history', async () => {
    const { harness, element } = await render(
      '/admin/products?page=999&name=Keyboard&extra=1',
      false,
      false,
    );
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const navigation = nextNavigation();
    http.expectOne('/api/product?page=998&size=4&name=Keyboard').flush({
      ...emptyPage,
      page: 998,
      totalElements: 9,
      totalPages: 3,
    });
    await Promise.resolve();
    TestBed.tick();
    harness.detectChanges();
    expect.soft(element.textContent).not.toContain('No products found.');
    expect.soft(element.querySelectorAll('p-skeleton')).toHaveLength(4);
    await navigation;
    TestBed.tick();
    harness.detectChanges();
    expect.soft(element.querySelectorAll('p-skeleton')).toHaveLength(4);
    expect.soft(element.textContent).not.toContain('No products found.');
    expect(queryParams()).toEqual({ page: '3', name: 'Keyboard', extra: '1' });
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({
        queryParams: { page: 3 },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      }),
    );
    http.expectOne('/api/product?page=2&size=4&name=Keyboard').flush({
      ...productPage,
      page: 2,
      totalElements: 9,
      totalPages: 3,
    });
    await settle(harness);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelectorAll('article')).toHaveLength(4);
  });

  it('waits for the corrected page before scrolling when pagination returns out of range', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush({
      ...productPage,
      totalElements: 9,
      totalPages: 3,
    });
    await settle(harness);
    const results = element.querySelector<HTMLElement>('[aria-busy]');
    if (!results) throw new Error('Results region missing');
    const scroll = vi.fn();
    results.scrollIntoView = scroll;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    element.querySelector<HTMLButtonElement>('[aria-label="Page 3"]')?.click();
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
    expect.soft(element.textContent).not.toContain('No products found.');
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

  it('waits for new results before correcting a page using the previous total', async () => {
    const { harness } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    await harness.navigateByUrl('/admin/products?page=3&category=Monitors');
    TestBed.tick();
    expect(queryParams()).toEqual({ page: '3', category: 'Monitors' });
    http.expectOne('/api/product?page=2&size=4&category=Monitors').flush({
      ...productPage,
      page: 2,
      totalElements: 12,
      totalPages: 3,
    });
    await settle(harness);
    expect(queryParams()).toEqual({ page: '3', category: 'Monitors' });
  });

  it('preserves the same cards with reduced opacity while changing filters', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    const cards = Array.from(element.querySelectorAll('article'));
    await harness.navigateByUrl('/admin/products?name=Monitor&category=Monitors');
    TestBed.tick();
    harness.detectChanges();
    expect(element.querySelectorAll('article')).toHaveLength(4);
    Array.from(element.querySelectorAll('article')).forEach((card, index) =>
      expect(card).toBe(cards[index]),
    );
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    expect(element.querySelector('ul[aria-label="Products"]')?.classList).toContain('opacity-50');
    http.expectOne('/api/product?page=0&size=4&name=Monitor&category=Monitors').flush({
      ...productPage,
      content: [{ ...products[0], id: 'monitor', name: 'Monitor' }],
      totalElements: 1,
    });
    await settle(harness);
    expect(element.querySelectorAll('article')).toHaveLength(1);
    expect(element.querySelector('article')?.textContent).toContain('Monitor');
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('false');
    expect(element.querySelector('ul[aria-label="Products"]')?.classList).not.toContain(
      'opacity-50',
    );
    expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain(
      '1 product found.',
    );
  });

  it('preserves the last successful page when retrying a failed filter', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    http.expectOne('/api/product?page=0&size=4').flush(productPage);
    await settle(harness);
    await harness.navigateByUrl('/admin/products?name=Monitor');
    TestBed.tick();
    http
      .expectOne('/api/product?page=0&size=4&name=Monitor')
      .flush({ detail: 'Temporary failure.' }, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.textContent).toContain('Temporary failure.');
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Try again')
      ?.click();
    TestBed.tick();
    harness.detectChanges();
    expect(element.querySelectorAll('article')).toHaveLength(4);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    http.expectOne('/api/product?page=0&size=4&name=Monitor').flush(emptyPage);
    await settle(harness);
    expect(element.querySelectorAll('article')).toHaveLength(0);
    expect(element.textContent).toContain('No products found.');
  });

  it('shows an empty state without an additional action when there are no filters', async () => {
    const { element } = await render();
    expect(element.textContent).toContain('No products found.');
    expect(
      Array.from(element.querySelectorAll('button')).some(
        (button) => button.textContent?.trim() === 'Clear filters',
      ),
    ).toBe(false);
  });

  it('clears filters using the empty state action', async () => {
    const { harness, element } = await render('/admin/products?name=Keyboard');
    expect(element.textContent).toContain('No products found.');
    const clear = Array.from(element.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Clear filters',
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
    [{ detail: 'Service unavailable.' }, 'Service unavailable.'],
    [{}, 'Unable to load products. Please try again.'],
  ])(
    'shows an error and allows reloading without returning to skeletons',
    async (problem, message) => {
      const { harness, element } = await render('/admin/products', false, false);
      http.expectOne('/api/product?page=0&size=4').flush(problem, {
        status: 503,
        statusText: 'Unavailable',
      });
      await settle(harness);
      expect(element.querySelector('p-message')?.textContent).toContain(message);
      const retry = Array.from(element.querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Try again',
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
    },
  );
  async function renderActions() {
    const result = await render('/admin/products?name=Keyboard&extra=1', false, false);
    http.expectOne((req) => req.url === '/api/product').flush(productPage);
    await settle(result.harness);
    return result;
  }

  it('EDIT navigates to editing while preserving query parameters', async () => {
    const { element } = await renderActions();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    element.querySelector<HTMLButtonElement>('article button')?.click();
    expect(navigate).toHaveBeenCalledWith(['/admin/products', '0', 'edit'], {
      queryParamsHandling: 'preserve',
    });
  });

  it('opens confirmation with the name and focus on cancel; cancel does not delete', async () => {
    const { element } = await renderActions();
    const confirm = vi.spyOn(TestBed.inject(ConfirmationService), 'confirm');
    element.querySelectorAll<HTMLButtonElement>('article button')[1]?.click();
    const confirmation = confirm.mock.calls[0]?.[0];
    expect(confirmation?.message).toContain('Delete "Product 0"?');
    expect(confirmation?.defaultFocus).toBe('reject');
    expect(confirmation?.acceptLabel).toBe('Delete');
    expect(confirmation?.rejectLabel).toBe('Cancel');
    confirmation?.reject?.();
    http.expectNone((req) => req.method === 'DELETE');
  });

  it('deletes with ETag, blocks duplicate confirmation and reloads without skeletons', async () => {
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
    expect(element.textContent).toContain('No products found.');
  });

  it.each([1, 2])(
    'after deleting on page 2, keeps or corrects the page according to total %s',
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
      expect.soft(element.textContent).not.toContain('No products found.');
      if (navigation) {
        await navigation;
        TestBed.tick();
        harness.detectChanges();
        expect.soft(element.textContent).not.toContain('No products found.');
        expect.soft(element.querySelectorAll('article')).toHaveLength(1);
        http.expectOne('/api/product?page=0&size=4').flush(productPage);
      }
      await settle(harness);
      expect(queryParams()).toEqual({ page: String(totalPages), extra: '1' });
      expect(element.querySelectorAll('article')).toHaveLength(totalPages === 1 ? 4 : 1);
    },
  );

  it.each([
    [412, 'changed'],
    [404, 'no longer exists'],
    [503, 'Deletion failed.'],
  ])('handles error %s on deletion', async (status, message) => {
    const { harness, element } = await renderActions();
    const messages = vi.spyOn(TestBed.inject(MessageService), 'add');
    const confirm = vi.spyOn(TestBed.inject(ConfirmationService), 'confirm');
    element.querySelectorAll<HTMLButtonElement>('article button')[1]?.click();
    confirm.mock.calls[0]?.[0].accept?.();
    http
      .expectOne('/api/product/0')
      .flush({ detail: 'Deletion failed.' }, { status, statusText: 'Error' });
    TestBed.tick();
    if (status === 503) http.expectNone((req) => req.url === '/api/product');
    else http.expectOne((req) => req.url === '/api/product').flush(productPage);
    await settle(harness);
    expect(messages).toHaveBeenCalledWith(
      expect.objectContaining({ detail: expect.stringContaining(message) }),
    );
  });
  it('renders the confirmation name literally without creating HTML elements', async () => {
    const { harness, element } = await render('/admin/products', false, false);
    const name = '<img src=x onerror=alert(1)>Product';
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
      (button) => button.textContent?.trim() === 'Cancel',
    );
    cancel?.click();
    await settle(harness);
    http.expectNone((req) => req.method === 'DELETE');
  });
  it('queries stock in batches per page and distinguishes missing stock from unavailable stock', async () => {
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
    expect(element.querySelectorAll('article')[0]?.textContent).toContain('Stock: 15');
    expect(element.querySelectorAll('article')[1]?.textContent).toContain('Stock: 0');
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
      .flush({ detail: 'Stock query unavailable.' }, { status: 503, statusText: 'Unavailable' });
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(element.textContent).toContain('Stock query unavailable.');
    expect(element.querySelector('article')?.textContent).not.toContain('Stock: 0');
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Reload stock')
      ?.click();
    TestBed.tick();
    http
      .expectOne('/api/inventory?skuCode=4')
      .flush([{ skuCode: '4', quantity: 7, inStock: true }]);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(element.querySelector('article')?.textContent).toContain('Stock: 7');
    expect(element.textContent).not.toContain('Stock query unavailable.');
  });
});
