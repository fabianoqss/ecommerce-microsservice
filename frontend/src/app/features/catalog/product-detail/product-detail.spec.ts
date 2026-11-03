import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { NavigationEnd, provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { filter, firstValueFrom } from 'rxjs';
import { catalogRoutes } from '../catalog.routes';
import { Product } from '../data-access/product';

describe('ProductDetail', () => {
  let http: HttpTestingController;
  const product: Product = {
    id: '670000000000000000000001',
    name: 'Teclado Mecânico TKL',
    description: 'Teclado compacto.\nCom teclas iluminadas.',
    price: 349.9,
    category: 'Periféricos',
    imageUrl: '/images/teclado.png',
    version: 0,
  };
  const url = '/catalog/' + product.id;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [{ path: 'catalog', title: 'Catálogo | DS Catalog', children: catalogRoutes }],
          withComponentInputBinding(),
        ),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(path = url) {
    const harness = await RouterTestingHarness.create(path);
    TestBed.tick();
    const element = harness.routeNativeElement;
    if (!element) {
      throw new Error('A rota do produto não foi renderizada');
    }
    return { harness, element };
  }

  async function settle(harness: RouterTestingHarness) {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  it('mostra skeleton de imagem, nome, preço e descrição enquanto carrega', async () => {
    const { harness, element } = await render();
    expect(element.querySelectorAll('p-skeleton')).toHaveLength(4);
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('true');
    expect(element.querySelector('[role="status"]')?.textContent).toContain('Carregando produto.');
    expect(TestBed.inject(Title).getTitle()).toBe('Produto | DS Catalog');
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('[aria-busy]')?.getAttribute('aria-busy')).toBe('false');
  });

  it('mostra nome, preço, descrição como texto e imagem acessível e atualiza o título', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    expect(element.querySelector('h1')?.textContent?.trim()).toBe(product.name);
    expect(element.querySelector('[data-testid="price-integer"]')?.textContent).toBe('349');
    expect(element.querySelector('[data-testid="price-decimal"]')?.textContent).toBe(',90');
    expect(element.querySelector('h2')?.textContent?.trim()).toBe('Descrição do produto');
    expect(element.querySelector('.whitespace-pre-line')?.textContent?.trim()).toBe(
      product.description,
    );
    const image = element.querySelector('img');
    expect(image?.alt).toBe(product.name);
    expect(image?.getAttribute('src')).toBe(product.imageUrl);
    expect(image?.width).toBe(353);
    expect(image?.height).toBe(353);
    expect(TestBed.inject(Title).getTitle()).toBe(product.name + ' | DS Catalog');
  });

  it('mostra produto não encontrado para 404 e oferece retorno ao catálogo', async () => {
    const { harness, element } = await render('/catalog/id-invalido');
    http
      .expectOne('/api/product/id-invalido')
      .flush({ status: 404, title: 'Não encontrado' }, { status: 404, statusText: 'Not Found' });
    await settle(harness);
    expect(element.textContent).toContain('Produto não encontrado.');
    const back = Array.from(element.querySelectorAll('a')).find((link) =>
      link.textContent?.includes('Voltar ao catálogo'),
    );
    expect(back?.getAttribute('href')).toBe('/catalog');
    expect(element.querySelector('p-message')).toBeNull();
    expect(TestBed.inject(Title).getTitle()).toBe('Produto | DS Catalog');
  });

  it.each([
    [{ detail: 'Serviço indisponível.' }, 'Serviço indisponível.'],
    [{}, 'Não foi possível carregar o produto. Tente novamente.'],
    [{ detail: '  ' }, 'Não foi possível carregar o produto. Tente novamente.'],
  ])('mostra erro e permite tentar novamente', async (problem, message) => {
    const { harness, element } = await render();
    http
      .expectOne('/api/product/' + product.id)
      .flush(problem, { status: 503, statusText: 'Unavailable' });
    await settle(harness);
    expect(element.querySelector('p-message')?.getAttribute('severity')).toBe('error');
    expect(element.querySelector('p-message')?.textContent).toContain(message);
    element.querySelector<HTMLButtonElement>('button')?.click();
    TestBed.tick();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    expect(element.querySelector('p-message')).toBeNull();
    expect(element.querySelector('h1')?.textContent).toContain(product.name);
  });

  it('mostra placeholder quando não há imagem', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush({ ...product, imageUrl: null });
    await settle(harness);
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('.pi-image')).not.toBeNull();
  });

  it('mostra mensagem quando não há descrição e não interpreta HTML', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush({ ...product, description: '' });
    await settle(harness);
    expect(element.textContent).toContain('Este produto ainda não tem descrição.');
    await harness.navigateByUrl('/catalog/outro-id');
    TestBed.tick();
    http.expectOne('/api/product/outro-id').flush({ ...product, description: '<b>Descrição</b>' });
    await settle(harness);
    expect(element.textContent).toContain('<b>Descrição</b>');
    expect(element.querySelector('b')).toBeNull();
  });

  it('acompanha o id da rota e redefine o título enquanto carrega outro produto', async () => {
    const { harness, element } = await render();
    http.expectOne('/api/product/' + product.id).flush(product);
    await settle(harness);
    await harness.navigateByUrl('/catalog/outro-id');
    TestBed.tick();
    expect(TestBed.inject(Title).getTitle()).toBe('Produto | DS Catalog');
    expect(element.querySelector('h1')).toBeNull();
    http.expectOne('/api/product/outro-id').flush({ ...product, id: 'outro-id', name: 'Monitor' });
    await settle(harness);
    expect(element.querySelector('h1')?.textContent).toContain('Monitor');
    expect(TestBed.inject(Title).getTitle()).toBe('Monitor | DS Catalog');
  });

  it.each(['', '?page=2&name=Teclado&category=Perif%C3%A9ricos'])(
    'VOLTAR navega ao catálogo com os query params de origem: %s',
    async (query) => {
      const { harness, element } = await render(url + query);
      http.expectOne('/api/product/' + product.id).flush(product);
      await settle(harness);
      const back = Array.from(element.querySelectorAll('a')).find(
        (link) => link.textContent?.trim() === 'VOLTAR',
      );
      expect(back?.getAttribute('href')).toBe('/catalog' + query);
      const navigation = firstValueFrom(
        TestBed.inject(Router).events.pipe(filter((event) => event instanceof NavigationEnd)),
      );
      back?.click();
      await navigation;
      TestBed.tick();
      expect(TestBed.inject(Router).url).toBe('/catalog' + query);
      http.expectOne('/api/product/categories').flush([]);
      const request = http.expectOne((request) => request.url === '/api/product');
      expect(request.request.params.get('page')).toBe(query ? '1' : '0');
      expect(request.request.params.get('name')).toBe(query ? 'Teclado' : null);
      request.flush({ content: [], page: query ? 1 : 0, size: 8, totalElements: 0, totalPages: 0 });
      await settle(harness);
      expect(TestBed.inject(Title).getTitle()).toBe('Catálogo | DS Catalog');
    },
  );
});
