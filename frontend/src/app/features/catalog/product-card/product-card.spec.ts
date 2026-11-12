import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Product } from '../../../shared/data-access/product/product';
import { ProductCard } from './product-card';

describe('ProductCard', () => {
  const product: Product = {
    id: '1',
    name: 'TKL Mechanical Keyboard',
    description: 'Compact keyboard',
    price: 1999,
    category: 'Peripherals',
    imageUrl: null,
    version: 0,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });

  async function render(imageUrl: string | null = null) {
    const fixture = TestBed.createComponent(ProductCard);
    fixture.componentRef.setInput('product', { ...product, imageUrl });
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('wraps the article in a detail link while preserving query parameters', async () => {
    await TestBed.inject(Router).navigateByUrl('/?page=2&name=Keyboard');
    const element = await render();
    const link = element.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/catalog/1?page=2&name=Keyboard');
    expect(link?.querySelector('article h2')?.textContent).toContain(product.name);
    expect(link?.classList.contains('focus-visible:outline-primary')).toBe(true);
  });

  it('shows name and price with separate integer and fractional parts', async () => {
    const element = await render();
    expect(element.querySelector('article h2')?.textContent).toContain(product.name);
    expect(element.querySelector('[data-testid="price-integer"]')?.textContent).toBe('1,999');
    expect(element.querySelector('[data-testid="price-decimal"]')?.textContent).toBe('.00');
    expect(element.textContent).toContain('R$');
  });

  it('shows a placeholder when there is no image', async () => {
    const element = await render();
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('.pi-image')).not.toBeNull();
  });

  it('loads an image lazily with an accessible name and dimensions', async () => {
    const element = await render('/images/keyboard.png');
    const image = element.querySelector('img');
    expect(image?.alt).toBe(product.name);
    expect(image?.getAttribute('loading')).toBe('lazy');
    expect(image?.getAttribute('decoding')).toBe('async');
    expect(image?.width).toBe(158);
    expect(image?.height).toBe(158);
  });
});
