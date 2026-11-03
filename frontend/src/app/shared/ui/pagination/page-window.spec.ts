import { pageWindow } from './page-window';

describe('pageWindow', () => {
  it('mostra todas as páginas quando cabem na janela', () => {
    expect(pageWindow(0, 3, 10)).toEqual([0, 1, 2]);
  });

  it('preserva os extremos e a página atual em uma janela intermediária', () => {
    const pages = pageWindow(17, 35, 10);
    expect(pages[0]).toBe(0);
    expect(pages.at(-1)).toBe(34);
    expect(pages).toContain(17);
    expect(pages).toContain('ellipsis-start');
    expect(pages).toContain('ellipsis-end');
    expect(pages.filter((page) => typeof page === 'number')).toHaveLength(10);
  });

  it.each([0, 1, 17, 33, 34])('limita a janela móvel na página %s', (current) => {
    const pages = pageWindow(current, 35, 6);
    const numbers = pages.filter((page) => typeof page === 'number');
    expect(numbers).toHaveLength(6);
    expect(numbers).toContain(current);
    expect(new Set(pages).size).toBe(pages.length);
    expect(numbers).toEqual([...numbers].sort((a, b) => a - b));
  });

  it('mostra as últimas páginas sem lacunas no fim', () => {
    expect(pageWindow(34, 35, 6)).toEqual([0, 'ellipsis-start', 30, 31, 32, 33, 34]);
  });

  it('retorna uma janela vazia quando não há páginas', () => {
    expect(pageWindow(0, 0, 6)).toEqual([]);
  });
});
