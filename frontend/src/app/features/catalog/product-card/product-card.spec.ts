import { TestBed } from '@angular/core/testing';
import { Product } from '../data-access/product';
import { ProductCard } from './product-card';

describe('ProductCard', () => {
  const product: Product = {
    id: '1',
    name: 'Teclado Mecânico TKL',
    description: 'Teclado compacto',
    price: 1999,
    category: 'Periféricos',
    imageUrl: null,
    version: 0,
  };

  async function render(imageUrl: string | null = null) {
    const fixture = TestBed.createComponent(ProductCard);
    fixture.componentRef.setInput('product', { ...product, imageUrl });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('mostra nome e preço com inteiro e centavos separados', async () => {
    const element = await render();
    expect(element.querySelector('article h2')?.textContent).toContain(product.name);
    expect(element.querySelector('[data-testid="price-integer"]')?.textContent).toBe('1.999');
    expect(element.querySelector('[data-testid="price-decimal"]')?.textContent).toBe(',00');
    expect(element.textContent).toContain('R$');
  });

  it('mostra placeholder quando não há imagem', async () => {
    const element = await render();
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('.pi-image')).not.toBeNull();
  });

  it('carrega imagem de forma adiada, com nome acessível e dimensões', async () => {
    const element = await render('/images/teclado.png');
    const image = element.querySelector('img');
    expect(image?.alt).toBe(product.name);
    expect(image?.getAttribute('loading')).toBe('lazy');
    expect(image?.getAttribute('decoding')).toBe('async');
    expect(image?.width).toBe(158);
    expect(image?.height).toBe(158);
  });
});
