import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CartLineView } from '../../../shared/data-access/cart-view/cart-line-view';
import { CartLine } from './cart-line';

const line: CartLineView = {
  productId: 'a',
  quantity: 2,
  stock: 5,
  status: 'ok',
  product: {
    id: 'a',
    name: 'Teclado',
    price: 12.5,
    description: '',
    category: '',
    imageUrl: null,
    version: 0,
  },
};

describe('CartLine', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  async function render(value = line) {
    const fixture = TestBed.createComponent(CartLine);
    fixture.componentRef.setInput('line', value);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    return { fixture, element };
  }

  it('mostra nome, preço unitário, total e quantidade acessível limitada ao estoque', async () => {
    const { element } = await render();
    expect(element.textContent).toContain('Teclado');
    expect(element.textContent).toContain('R$ 12,50');
    expect(element.textContent).toContain('R$ 25,00');
    const input = element.querySelector('input');
    expect(input?.getAttribute('aria-valuemin')).toBe('1');
    expect(input?.getAttribute('aria-valuemax')).toBe('5');
    expect(element.querySelector('label')?.textContent).toContain('Quantidade de Teclado');
    expect(input?.id).toBe(element.querySelector('label')?.htmlFor);
  });

  it('emite alterações inteiras válidas e remoção a partir dos controles', async () => {
    const { fixture, element } = await render();
    const quantity = vi.fn();
    const remove = vi.fn();
    fixture.componentInstance.quantityChange.subscribe(quantity);
    fixture.componentInstance.remove.subscribe(remove);
    const input = element.querySelector('input');
    if (!input) throw new Error('Quantidade ausente');
    input.value = '3';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(quantity).toHaveBeenCalledWith(3);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Remover'))
      ?.click();
    expect(remove).toHaveBeenCalledTimes(1);
  });

  it('limita a quantidade a 10000 mesmo com estoque maior', async () => {
    const { element } = await render({ ...line, stock: 20000 });
    expect(element.querySelector('input')?.getAttribute('aria-valuemax')).toBe('10000');
  });

  function quantityInput(element: HTMLElement): HTMLInputElement {
    const input = element.querySelector('input');
    if (!input) throw new Error('Quantidade ausente');
    return input;
  }

  it('permite apagar a quantidade e digitar 3 sem inserir zero', async () => {
    const { fixture, element } = await render({ ...line, stock: 100 });
    const change = vi.fn();
    fixture.componentInstance.quantityChange.subscribe(change);
    const input = quantityInput(element);
    input.setSelectionRange(1, 1);
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true, cancelable: true }),
    );
    expect(input.value).toBe('');
    expect(change).not.toHaveBeenCalled();
    input.dispatchEvent(
      new KeyboardEvent('keypress', {
        key: '3',
        keyCode: 51,
        charCode: 51,
        bubbles: true,
        cancelable: true,
      }),
    );
    await fixture.whenStable();
    expect(input.value).toBe('3');
    expect(change).toHaveBeenCalledExactlyOnceWith(3);
  });

  it.each([
    ['Home', 1],
    ['End', 5],
    ['ArrowUp', 3],
    ['ArrowDown', 1],
  ] as const)('emite a quantidade %s pelo teclado', async (key, value) => {
    const { fixture, element } = await render();
    const change = vi.fn();
    fixture.componentInstance.quantityChange.subscribe(change);
    const input = quantityInput(element);
    input.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    await fixture.whenStable();
    input.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(change).toHaveBeenCalledExactlyOnceWith(value);
    expect(input.value).toBe(String(value));
  });

  it.each(['', 'abc', '0', '-1', '6', '10001', '2.5'])(
    'ignora a quantidade inválida %s e restaura a atual ao perder foco',
    async (value) => {
      const { fixture, element } = await render();
      const change = vi.fn();
      fixture.componentInstance.quantityChange.subscribe(change);
      const input = quantityInput(element);
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      expect(change).not.toHaveBeenCalled();
      input.dispatchEvent(new Event('blur'));
      await fixture.whenStable();
      expect(change).not.toHaveBeenCalled();
      expect(input.value).toBe('2');
    },
  );

  it('não emite quantidade acima de 10000 mesmo com estoque maior', async () => {
    const { fixture, element } = await render({ ...line, stock: 20000 });
    const change = vi.fn();
    fixture.componentInstance.quantityChange.subscribe(change);
    const input = quantityInput(element);
    input.value = '10001';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(change).not.toHaveBeenCalled();
    expect(input.value).toBe('2');
  });

  it('permite diminuir quantidade insuficiente mesmo ainda acima do estoque', async () => {
    const { fixture, element } = await render({
      ...line,
      quantity: 10,
      stock: 5,
      status: 'insufficient-stock',
    });
    const change = vi.fn();
    fixture.componentInstance.quantityChange.subscribe(change);
    const input = quantityInput(element);
    input.value = '8';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(change).toHaveBeenCalledExactlyOnceWith(8);
  });

  it('desabilita quantidade de produto sem estoque', async () => {
    const { element } = await render({ ...line, stock: 0, status: 'out-of-stock' });
    expect(quantityInput(element).disabled).toBe(true);
  });

  it.each(['out-of-stock', 'insufficient-stock', 'error'] as const)(
    'mantém máximo acessível acima da quantidade exibida e não mostra total para %s',
    async (status) => {
      const { element } = await render({ ...line, stock: 1, quantity: 10, status });
      const input = quantityInput(element);
      expect(input.value).toBe('10');
      expect(input.getAttribute('aria-valuemax')).toBe('10');
      expect(element.textContent).not.toContain('Total:');
    },
  );

  it.each(['unavailable', 'error'] as const)(
    'identifica linhas %s sem nome por ID e mantém remoção acessível',
    async (status) => {
      const { fixture, element } = await render({ ...line, product: null, status });
      const other = await render({ ...line, productId: 'b', product: null, status });
      expect(element.querySelector('article')?.getAttribute('aria-label')).toBe(
        'Produto indisponível (a)',
      );
      expect(other.element.querySelector('article')?.getAttribute('aria-label')).toBe(
        'Produto indisponível (b)',
      );
      const button = element.querySelector('button[aria-label="Remover produto indisponível a"]');
      expect(button).not.toBeNull();
      const remove = vi.fn();
      fixture.componentInstance.remove.subscribe(remove);
      (button as HTMLButtonElement | null)?.click();
      expect(remove).toHaveBeenCalledTimes(1);
      expect(element.querySelector('input')).toBeNull();
      expect(element.textContent).not.toContain('Total:');
      if (status === 'error') {
        expect(element.textContent).toContain('Não foi possível carregar este produto.');
      }
    },
  );

  it.each([
    ['unavailable', 'Produto indisponível'],
    ['out-of-stock', 'Sem estoque'],
    ['insufficient-stock', 'Apenas 5 em estoque'],
  ] as const)('mostra estado %s e mantém remoção disponível', async (status, message) => {
    const { element } = await render({
      ...line,
      status,
      product: status === 'unavailable' ? null : line.product,
    });
    expect(element.textContent).toContain(message);
    expect(
      Array.from(element.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Remover'),
      )?.disabled,
    ).toBe(false);
    if (status === 'unavailable') expect(element.querySelector('input')).toBeNull();
  });
});
