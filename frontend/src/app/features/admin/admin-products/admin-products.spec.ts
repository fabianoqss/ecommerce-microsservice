import { TestBed } from '@angular/core/testing';
import { AdminProducts } from './admin-products';

describe('AdminProducts', () => {
  it('identifica a página com um título disponível para leitores de tela', async () => {
    TestBed.configureTestingModule({ imports: [AdminProducts] });
    const fixture = TestBed.createComponent(AdminProducts);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const heading = element.querySelector('h1');

    expect(heading?.textContent).toBe('Produtos');
    expect(heading?.classList.contains('sr-only')).toBe(true);
    expect(heading?.hasAttribute('aria-hidden')).toBe(false);
  });
});
