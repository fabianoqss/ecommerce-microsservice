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

  async function render(edit = false, complete = true) {
    harness = await RouterTestingHarness.create(
      '/admin/products/' + (edit ? '1/edit' : 'new') + '?name=Teclado&extra=1',
    );
    TestBed.tick();
    http.expectOne('/api/product/categories').flush(['Periféricos']);
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
  ) {
    for (const [key, value] of Object.entries({ name, description, category, price })) {
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
    req.flush(product, { status: 201, statusText: 'Created' });
    await settle();
    expect(TestBed.inject(Router).url).toBe('/admin/products?name=Teclado&extra=1');
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success' }));
  });

  it('carrega edição, formata preço e reenvia a imagem existente e ETag', async () => {
    await render(true);
    expect(field('price').value).toBe('1.234,56');
    expect(field('name').value).toBe('Teclado');
    submit();
    const req = http.expectOne('/api/product/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body.imageUrl).toBe('https://example.com/image.png');
    req.flush({ ...product, version: 4 });
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
    await settle();
    expect(field('name').value).toBe('Atualizado');
    submit();
    const req = http.expectOne('/api/product/1');
    expect(req.request.headers.get('If-Match')).toBe('"8"');
    expect(req.request.body.imageUrl).toBe('https://example.com/new.png');
    req.flush({ ...product, version: 9 });
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
    await settle();
  });
});
