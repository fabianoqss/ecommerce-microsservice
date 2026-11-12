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
    name: 'Keyboard',
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

  it('shows name, unit price, total and accessible quantity limited to stock', async () => {
    const { element } = await render();
    expect(element.textContent).toContain('Keyboard');
    expect(element.textContent).toContain('R$12.50');
    expect(element.textContent).toContain('R$25.00');
    const input = element.querySelector('input');
    expect(input?.getAttribute('aria-valuemin')).toBe('1');
    expect(input?.getAttribute('aria-valuemax')).toBe('5');
    expect(element.querySelector('label')?.textContent).toContain('Quantity for Keyboard');
    expect(input?.id).toBe(element.querySelector('label')?.htmlFor);
  });

  it('emits valid integer changes and removal from controls', async () => {
    const { fixture, element } = await render();
    const quantity = vi.fn();
    const remove = vi.fn();
    fixture.componentInstance.quantityChange.subscribe(quantity);
    fixture.componentInstance.remove.subscribe(remove);
    const input = element.querySelector('input');
    if (!input) throw new Error('Quantity missing');
    input.value = '3';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(quantity).toHaveBeenCalledWith(3);
    Array.from(element.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Remove'))
      ?.click();
    expect(remove).toHaveBeenCalledTimes(1);
  });

  it('limits quantity to 10000 even with higher stock', async () => {
    const { element } = await render({ ...line, stock: 20000 });
    expect(element.querySelector('input')?.getAttribute('aria-valuemax')).toBe('10000');
  });

  function quantityInput(element: HTMLElement): HTMLInputElement {
    const input = element.querySelector('input');
    if (!input) throw new Error('Quantity missing');
    return input;
  }

  it('allows clearing the quantity and typing 3 without inserting zero', async () => {
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
  ] as const)('emits quantity %s using the keyboard', async (key, value) => {
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
    'ignores invalid quantity %s and restores the current value on blur',
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

  it('does not emit quantities above 10000 even with higher stock', async () => {
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

  it('allows decreasing an insufficient quantity even when still above stock', async () => {
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

  it('disables quantity for an out-of-stock product', async () => {
    const { element } = await render({ ...line, stock: 0, status: 'out-of-stock' });
    expect(quantityInput(element).disabled).toBe(true);
  });

  it.each(['out-of-stock', 'insufficient-stock', 'error'] as const)(
    'keeps the accessible maximum above the displayed quantity and hides the total for %s',
    async (status) => {
      const { element } = await render({ ...line, stock: 1, quantity: 10, status });
      const input = quantityInput(element);
      expect(input.value).toBe('10');
      expect(input.getAttribute('aria-valuemax')).toBe('10');
      expect(element.textContent).not.toContain('Total:');
    },
  );

  it.each(['unavailable', 'error'] as const)(
    'identifies unnamed %s lines by ID and keeps removal accessible',
    async (status) => {
      const { fixture, element } = await render({ ...line, product: null, status });
      const other = await render({ ...line, productId: 'b', product: null, status });
      expect(element.querySelector('article')?.getAttribute('aria-label')).toBe(
        'Product unavailable (a)',
      );
      expect(other.element.querySelector('article')?.getAttribute('aria-label')).toBe(
        'Product unavailable (b)',
      );
      const button = element.querySelector('button[aria-label="Remove unavailable product a"]');
      expect(button).not.toBeNull();
      const remove = vi.fn();
      fixture.componentInstance.remove.subscribe(remove);
      (button as HTMLButtonElement | null)?.click();
      expect(remove).toHaveBeenCalledTimes(1);
      expect(element.querySelector('input')).toBeNull();
      expect(element.textContent).not.toContain('Total:');
      if (status === 'error') {
        expect(element.textContent).toContain('Unable to load this product.');
      }
    },
  );

  it.each([
    ['unavailable', 'Product unavailable'],
    ['out-of-stock', 'Out of stock'],
    ['insufficient-stock', 'Only 5 in stock'],
  ] as const)('shows state %s and keeps removal available', async (status, message) => {
    const { element } = await render({
      ...line,
      status,
      product: status === 'unavailable' ? null : line.product,
    });
    expect(element.textContent).toContain(message);
    expect(
      Array.from(element.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Remove'),
      )?.disabled,
    ).toBe(false);
    if (status === 'unavailable') expect(element.querySelector('input')).toBeNull();
  });
});
