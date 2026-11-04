import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NavigationEnd, provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, firstValueFrom } from 'rxjs';
import { AdminProducts } from './admin-products';

describe('AdminProducts', () => {
  let http: HttpTestingController;

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

  async function render(url = '/admin/products', failCategories = false) {
    const harness = await RouterTestingHarness.create(url);
    TestBed.tick();
    const request = http.expectOne('/api/product/categories');
    if (failCategories) {
      request.flush({}, { status: 503, statusText: 'Unavailable' });
    } else {
      request.flush(['Periféricos', 'Monitores']);
    }
    await harness.fixture.whenStable();
    harness.detectChanges();
    const element = harness.routeNativeElement;
    if (!element) {
      throw new Error('A página de produtos não foi renderizada');
    }
    return { harness, element };
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
    await harness.fixture.whenStable();
    expect(element.querySelector('input')?.value).toBe('Monitor');
    expect(element.querySelector('select')?.value).toBe('Monitores');
  });

  it('atualiza nome na URL preservando categoria e substituindo o histórico', async () => {
    const { element } = await render('/admin/products?category=Perif%C3%A9ricos');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const field = element.querySelector('input');
    if (!field) throw new Error('Campo de nome ausente');
    const navigation = nextNavigation();
    field.value = ' Teclado ';
    field.dispatchEvent(new Event('input'));
    await navigation;
    expect(queryParams()).toEqual({ name: 'Teclado', category: 'Periféricos' });
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ replaceUrl: true }));
  });

  it('atualiza categoria na URL preservando nome', async () => {
    const { element } = await render('/admin/products?name=Teclado');
    const select = element.querySelector('select');
    if (!select) throw new Error('Seletor de categoria ausente');
    const navigation = nextNavigation();
    select.value = 'Periféricos';
    select.dispatchEvent(new Event('change'));
    await navigation;
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
});
