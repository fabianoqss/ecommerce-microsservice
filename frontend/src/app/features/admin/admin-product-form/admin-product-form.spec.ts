import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MessageService } from 'primeng/api';
import { AdminProductForm } from './admin-product-form';

@Component({ template: '<h1>Produtos</h1>' })
class ListStub {}

const product = {
  id: '1',
  name: 'Teclado',
  description: 'Teclado mecânico',
  category: 'Periféricos',
  price: 1234.56,
  imageUrl: 'https://example.com/image.png',
  version: 3,
};

describe('AdminProductForm', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let element: HTMLElement;
  let toast: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
        provideRouter([
          { path: 'admin/products/new', component: AdminProductForm },
          { path: 'admin/products/:id/edit', component: AdminProductForm },
          { path: 'admin/products', component: ListStub },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    toast = vi.spyOn(TestBed.inject(MessageService), 'add');
  });
  afterEach(() => http.verify());

  async function render(edit = false, complete = true, quantity: number | null = 8) {
    harness = await RouterTestingHarness.create(
      '/admin/products/' + (edit ? '1/edit' : 'new') + '?name=Teclado&extra=1',
    );
    TestBed.tick();
    http.expectOne('/api/product/categories').flush(['Periféricos']);
    if (edit) {
      http
        .expectOne('/api/inventory?skuCode=1')
        .flush(quantity === null ? [] : [{ skuCode: '1', quantity, inStock: quantity > 0 }]);
    }
    if (edit && complete) http.expectOne('/api/product/1').flush(product);
    if (!edit || complete) await settle();
    else harness.detectChanges();
    const root = harness.routeNativeElement;
    if (!root) throw new Error('Formulário ausente');
    element = root;
  }

  async function settle() {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function field(label: string): HTMLInputElement | HTMLTextAreaElement {
    const control = element.querySelector<HTMLInputElement | HTMLTextAreaElement>('#' + label);
    if (!control) throw new Error('Campo ausente: ' + label);
    return control;
  }

  function fill(
    name = ' Teclado ',
    description = ' Teclado mecânico ',
    category = ' Nova categoria ',
    price = '1.234,56',
    quantity = '8',
  ) {
    for (const [key, value] of Object.entries({ name, description, category, price, quantity })) {
      const input = field(key);
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    harness.detectChanges();
  }

  function submit() {
    element
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));
    harness.detectChanges();
  }

  function click(text: string) {
    const button = Array.from(element.querySelectorAll('button')).find(
      (item) => item.textContent?.trim() === text,
    );
    if (!button) throw new Error('Ação ausente: ' + text);
    button.click();
  }

  function stockSaved(method: 'POST' | 'PUT' = 'POST', quantity = 8) {
    const request = http.expectOne(method === 'POST' ? '/api/inventory' : '/api/inventory/1');
    expect(request.request.method).toBe(method);
    expect(request.request.body).toEqual(
      method === 'POST' ? { skuCode: '1', quantity } : { quantity },
    );
    request.flush({ skuCode: '1', quantity, inStock: quantity > 0 });
  }

  it('cria com campos aparados, preço numérico e imagem nula e preserva os filtros', async () => {
    await render();
    expect(element.querySelector('datalist option')?.getAttribute('value')).toBe('Periféricos');
    fill();
    submit();
    const req = http.expectOne('/api/product');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      name: 'Teclado',
      description: 'Teclado mecânico',
      category: 'Nova categoria',
      price: 1234.56,
      imageUrl: null,
    });
    http.expectNone((req) => req.url.startsWith('/api/inventory'));
    req.flush(product, { status: 201, statusText: 'Created' });
    expect(toast).not.toHaveBeenCalled();
    stockSaved();
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products?name=Teclado&extra=1');
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success' }));
  });

  it('carrega edição, formata preço e reenvia a imagem existente e ETag', async () => {
    await render(true);
    expect(field('price').value).toBe('1.234,56');
    expect(field('name').value).toBe('Teclado');
    expect(field('quantity').value).toBe('8');
    submit();
    const req = http.expectOne('/api/product/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body.imageUrl).toBe('https://example.com/image.png');
    req.flush({ ...product, version: 4 });
    stockSaved('PUT');
    await settle();
  });

  it('não envia formulário inválido e conecta mensagens acessíveis aos campos', async () => {
    await render();
    submit();
    http.expectNone((req) => req.method === 'POST');
    expect(field('name').getAttribute('aria-invalid')).toBe('true');
    expect(field('name').getAttribute('aria-describedby')).toBe('name-error');
    expect(element.querySelector('#name-error')?.textContent).toContain('nome');
    fill('a'.repeat(256), 'curta', 'a'.repeat(101), '1234.56');
    submit();
    expect(element.textContent).toContain('1.234,56');
    http.expectNone((req) => req.method === 'POST');
  });

  it.each([
    [' ', 'Descrição válida', 'Categoria', '1'],
    ['Nome', ' '.repeat(10), 'Categoria', '1'],
    ['Nome', 'a'.repeat(2001), 'Categoria', '1'],
    ['Nome', 'Descrição válida', ' ', '1'],
    ['Nome', 'Descrição válida', 'Categoria', '1000000000000'],
  ])(
    'valida regras normalizadas antes de enviar: %s',
    async (name, description, category, price) => {
      await render();
      fill(name, description, category, price);
      submit();
      http.expectNone((req) => req.method === 'POST');
    },
  );

  it('impede envio duplicado enquanto salva e mostra estado ocupado', async () => {
    await render();
    fill();
    submit();
    submit();
    const requests = http.match((req) => req.method === 'POST');
    expect(requests).toHaveLength(1);
    expect(element.querySelector('form')?.getAttribute('aria-busy')).toBe('true');
    expect(element.textContent).toContain('SALVANDO');
    requests[0].flush(product);
    submit();
    http.expectNone('/api/product');
    stockSaved();
    await settle();
  });

  it('mapeia fields do 400 e mostra detail quando há campo desconhecido', async () => {
    await render();
    fill();
    submit();
    http.expectOne('/api/product').flush(
      {
        detail: 'Revise os dados enviados.',
        fields: { name: 'Nome já utilizado.', imageUrl: 'Imagem inválida.' },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(element.querySelector('#name-error')?.textContent).toContain('Nome já utilizado.');
    expect(element.textContent).toContain('Revise os dados enviados.');
    field('name').value = 'Outro nome';
    field('name').dispatchEvent(new Event('input'));
    harness.detectChanges();
    expect(element.querySelector('#name-error')).toBeNull();
  });

  it('mantém input no 412 e só substitui valores e versão após recarga explícita', async () => {
    await render(true);
    fill('Meu nome');
    submit();
    http
      .expectOne('/api/product/1')
      .flush({ detail: 'Conflito' }, { status: 412, statusText: 'Precondition Failed' });
    await settle();
    expect(field('name').value).toBe('Meu nome');
    expect(element.textContent).toContain('Outra pessoa alterou');
    http.expectNone('/api/product/1');
    click('Recarregar dados');
    TestBed.tick();
    http.expectOne('/api/product/1').flush({
      ...product,
      name: 'Atualizado',
      version: 8,
      imageUrl: 'https://example.com/new.png',
    });
    http
      .expectOne('/api/inventory?skuCode=1')
      .flush([{ skuCode: '1', quantity: 21, inStock: true }]);
    await settle();
    expect(field('name').value).toBe('Atualizado');
    expect(field('quantity').value).toBe('21');
    submit();
    const req = http.expectOne('/api/product/1');
    expect(req.request.headers.get('If-Match')).toBe('"8"');
    expect(req.request.body.imageUrl).toBe('https://example.com/new.png');
    req.flush({ ...product, version: 9 });
    stockSaved('PUT', 21);
    await settle();
  });

  it('cancela sem escrever e preserva query params', async () => {
    await render();
    click('CANCELAR');
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products?name=Teclado&extra=1');
    http.expectNone((req) => req.method !== 'GET');
  });

  it('mostra skeletons até concluir o carregamento da edição', async () => {
    await render(true, false);
    expect(element.querySelector('p-skeleton')).not.toBeNull();
    expect(element.querySelector('form')).toBeNull();
    http.expectOne('/api/product/1').flush(product);
    await settle();
    expect(element.querySelector('p-skeleton')).toBeNull();
    expect(element.querySelector('form')).not.toBeNull();
  });

  it('mostra produto ausente e link de retorno no 404', async () => {
    await render(true, false);
    http
      .expectOne('/api/product/1')
      .flush({ detail: 'Ausente' }, { status: 404, statusText: 'Not Found' });
    await settle();
    expect(element.textContent).toContain('Produto não encontrado');
    expect(element.querySelector('a')?.getAttribute('href')).toBe(
      '/admin/products?name=Teclado&extra=1',
    );
    expect(element.querySelector('form')).toBeNull();
  });

  it('mostra detail e permite repetir carga que falhou', async () => {
    await render(true, false);
    http
      .expectOne('/api/product/1')
      .flush({ detail: 'Falha temporária.' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(element.textContent).toContain('Falha temporária.');
    click('Tentar novamente');
    TestBed.tick();
    http.expectOne('/api/product/1').flush(product);
    await settle();
    expect(field('name').value).toBe('Teclado');
  });

  it('mostra erro de salvamento e permite nova tentativa manual', async () => {
    await render();
    fill();
    submit();
    http
      .expectOne('/api/product')
      .flush({ detail: 'Serviço indisponível.' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(element.textContent).toContain('Serviço indisponível.');
    expect(field('name').value).toBe(' Teclado ');
    submit();
    http.expectOne('/api/product').flush(product);
    stockSaved();
    await settle();
  });
  it('mapeia todos os campos conhecidos do 400 sem erro global redundante', async () => {
    await render();
    fill();
    submit();
    http.expectOne('/api/product').flush(
      {
        detail: 'Validação de campos.',
        fields: {
          name: 'Nome inválido.',
          category: 'Categoria inválida.',
          price: 'Preço inválido.',
          description: 'Descrição inválida.',
        },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    for (const [id, message] of Object.entries({
      name: 'Nome inválido.',
      category: 'Categoria inválida.',
      price: 'Preço inválido.',
      description: 'Descrição inválida.',
    })) {
      expect(element.querySelector('#' + id + '-error')?.textContent).toContain(message);
      expect(field(id).getAttribute('aria-invalid')).toBe('true');
    }
    expect(element.textContent).not.toContain('Validação de campos.');
  });

  it('cancela edição sem PUT e preserva filtros', async () => {
    await render(true);
    fill();
    click('CANCELAR');
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products?name=Teclado&extra=1');
    http.expectNone((req) => req.method !== 'GET');
  });

  it('permite criar categoria quando a consulta de categorias falha', async () => {
    harness = await RouterTestingHarness.create('/admin/products/new');
    TestBed.tick();
    http.expectOne('/api/product/categories').flush({}, { status: 503, statusText: 'Unavailable' });
    await settle();
    const root = harness.routeNativeElement;
    if (!root) throw new Error('Formulário ausente');
    element = root;
    fill();
    expect(field('category').disabled).toBe(false);
    submit();
    http.expectOne('/api/product').flush(product);
    stockSaved();
    await settle();
  });
  it.each(['', '-1', '1.5', '2147483648'])(
    'recusa estoque inválido %s com erro acessível',
    async (quantity) => {
      await render();
      fill(undefined, undefined, undefined, undefined, quantity);
      submit();
      http.expectNone((req) => req.method !== 'GET');
      expect(field('quantity').getAttribute('aria-invalid')).toBe('true');
      expect(field('quantity').getAttribute('aria-describedby')).toBe('quantity-error');
      expect(element.querySelector('#quantity-error')?.textContent).toContain('inteiro');
    },
  );

  it.each([0, 2147483647])('aceita o limite de estoque %s', async (quantity) => {
    await render();
    fill(undefined, undefined, undefined, undefined, String(quantity));
    submit();
    http.expectOne('/api/product').flush(product);
    stockSaved('POST', quantity);
    await settle();
  });

  it('prefill zero sem linha e cria estoque ao salvar a edição', async () => {
    await render(true, true, null);
    expect(field('quantity').value).toBe('0');
    submit();
    http.expectOne('/api/product/1').flush({ ...product, version: 4 });
    stockSaved('POST', 0);
    await settle();
  });

  it('cria estoque quando o PUT encontra uma linha removida', async () => {
    await render(true);
    submit();
    http.expectOne('/api/product/1').flush({ ...product, version: 4 });
    http.expectOne('/api/inventory/1').flush({}, { status: 404, statusText: 'Not Found' });
    stockSaved();
    await settle();
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success' }));
  });

  it.each([400, 409, 503])(
    'preserva produto criado e permite repetir após erro %s no estoque',
    async (status) => {
      await render();
      fill();
      submit();
      http.expectOne('/api/product').flush({ ...product, version: 4 });
      http
        .expectOne('/api/inventory')
        .flush(
          { detail: 'Falha no estoque.', fields: { quantity: 'Quantidade recusada.' } },
          { status, statusText: 'Error' },
        );
      await settle();
      expect(element.textContent).toContain('O produto foi salvo, mas o estoque não');
      expect(element.textContent).toContain('Falha no estoque.');
      expect(element.textContent).toContain('Tente salvar novamente');
      expect(field('quantity').value).toBe('8');
      expect(toast).not.toHaveBeenCalled();
      expect(TestBed.inject(Router).url).toContain('/admin/products/new');
      fill();
      submit();
      http.expectNone('/api/product');
      const update = http.expectOne('/api/product/1');
      expect(update.request.method).toBe('PUT');
      expect(update.request.headers.get('If-Match')).toBe('"4"');
      update.flush({ ...product, version: 5 });
      stockSaved('PUT');
      await settle();
      expect(TestBed.inject(Router).url).toBe('/admin/products?name=Teclado&extra=1');
    },
  );

  it('não faz POST para falha de PUT diferente de 404 e mantém a nova versão', async () => {
    await render(true);
    submit();
    http.expectOne('/api/product/1').flush({ ...product, version: 4 });
    http.expectOne('/api/inventory/1').flush({}, { status: 503, statusText: 'Unavailable' });
    await settle();
    http.expectNone('/api/inventory');
    expect(element.textContent).toContain('O produto foi salvo, mas o estoque não');
    expect(toast).not.toHaveBeenCalled();
    submit();
    const update = http.expectOne('/api/product/1');
    expect(update.request.headers.get('If-Match')).toBe('"4"');
    update.flush({ ...product, version: 5 });
    http.expectOne('/api/inventory/1').flush({}, { status: 404, statusText: 'Not Found' });
    stockSaved();
    await settle();
  });

  it('aguarda estoque e permite repetir consulta que falhou sem assumir zero', async () => {
    harness = await RouterTestingHarness.create('/admin/products/1/edit');
    TestBed.tick();
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product/1').flush(product);
    await Promise.resolve();
    harness.detectChanges();
    const root = harness.routeNativeElement;
    if (!root) throw new Error('Formulário ausente');
    element = root;
    expect(element.querySelector('form')).toBeNull();
    http
      .expectOne('/api/inventory?skuCode=1')
      .flush({ detail: 'Estoque indisponível.' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(element.textContent).toContain('Estoque indisponível.');
    expect(element.querySelector('form')).toBeNull();
    click('Tentar novamente');
    TestBed.tick();
    http
      .expectOne('/api/inventory?skuCode=1')
      .flush([{ skuCode: '1', quantity: 12, inStock: true }]);
    await settle();
    expect(field('quantity').value).toBe('12');
  });
  it('permite recarregar conflito após criar produto com falha de estoque', async () => {
    await render();
    fill();
    submit();
    http.expectOne('/api/product').flush({ ...product, version: 4 });
    http.expectOne('/api/inventory').flush({}, { status: 503, statusText: 'Unavailable' });
    await settle();
    submit();
    http.expectOne('/api/product/1').flush({}, { status: 412, statusText: 'Precondition Failed' });
    await settle();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    click('Recarregar dados');
    await navigate.mock.results[0]?.value;
    TestBed.tick();
    http.expectOne('/api/product/categories').flush([]);
    http.expectOne('/api/product/1').flush({ ...product, version: 8 });
    http.expectOne('/api/inventory?skuCode=1').flush([]);
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products/1/edit?name=Teclado&extra=1');
    const root = harness.routeNativeElement;
    if (!root) throw new Error('Formulário ausente');
    element = root;
    submit();
    const request = http.expectOne('/api/product/1');
    expect(request.request.headers.get('If-Match')).toBe('"8"');
    request.flush({ ...product, version: 9 });
    stockSaved('POST', 0);
    await settle();
  });
});
