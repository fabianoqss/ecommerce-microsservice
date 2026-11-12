import { TestBed } from '@angular/core/testing';
import { Product } from '../../../shared/data-access/product/product';
import { AdminProductCard } from './admin-product-card';

describe('AdminProductCard', () => {
  const product: Product = {
    id: '1',
    name: 'TKL Mechanical Keyboard',
    description: 'Compact keyboard',
    price: 2779,
    category: 'Peripherals',
    imageUrl: null,
    version: 0,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AdminProductCard] });
  });

  async function render(imageUrl: string | null = null) {
    const fixture = TestBed.createComponent(AdminProductCard);
    fixture.componentRef.setInput('product', { ...product, imageUrl });
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('shows the name, accessible formatted price and exactly one category', async () => {
    const { element } = await render();
    expect(element.querySelector('h2')?.textContent?.trim()).toBe(product.name);
    expect(element.querySelector('[data-testid="price-integer"]')?.textContent).toBe('2,779');
    expect(element.querySelector('[data-testid="price-decimal"]')?.textContent).toBe('.00');
    expect(element.querySelector('.sr-only')?.textContent).toBe('R$2,779.00');
    expect(element.querySelectorAll('[data-testid="category"]')).toHaveLength(1);
    expect(element.querySelector('[data-testid="category"]')?.textContent?.trim()).toBe(
      product.category,
    );
  });

  it('uses the catalog placeholder when there is no image', async () => {
    const { element } = await render();
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('.pi-image')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('shows an image with an accessible name and lazy loading', async () => {
    const { element } = await render('/images/keyboard.png');
    const image = element.querySelector('img');
    expect(image?.getAttribute('src')).toBe('/images/keyboard.png');
    expect(image?.alt).toBe(product.name);
    expect(image?.getAttribute('loading')).toBe('lazy');
    expect(image?.width).toBe(103);
    expect(image?.height).toBe(103);
    expect(element.querySelector('.pi-image')).toBeNull();
  });

  it('emits the product on edit and delete using accessible buttons', async () => {
    const { fixture, element } = await render();
    const edit = vi.fn();
    const remove = vi.fn();
    fixture.componentInstance.edit.subscribe(edit);
    fixture.componentInstance.remove.subscribe(remove);
    const buttons = element.querySelectorAll('button');
    expect(buttons).toHaveLength(2);
    expect(buttons[0]?.type).toBe('button');
    expect(buttons[0]?.getAttribute('aria-label')).toBe('Edit ' + product.name);
    expect(buttons[1]?.type).toBe('button');
    expect(buttons[1]?.getAttribute('aria-label')).toBe('Delete ' + product.name);
    buttons[0]?.click();
    buttons[1]?.click();
    expect(edit).toHaveBeenCalledExactlyOnceWith(product);
    expect(remove).toHaveBeenCalledExactlyOnceWith(product);
  });
  it.each([0, 12])('shows the provided stock quantity %s', async (quantity) => {
    const { fixture, element } = await render();
    fixture.componentRef.setInput('quantity', quantity);
    fixture.detectChanges();
    expect(element.textContent).toContain('Stock: ' + quantity);
  });

  it('does not show unknown stock as zero', async () => {
    const { element } = await render();
    expect(element.textContent).not.toContain('Stock: 0');
  });
});
