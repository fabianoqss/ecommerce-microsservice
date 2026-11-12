import { pageWindow } from './page-window';

describe('pageWindow', () => {
  it('shows all pages when they fit in the window', () => {
    expect(pageWindow(0, 3, 10)).toEqual([0, 1, 2]);
  });

  it('preserves endpoints and the current page in a middle window', () => {
    const pages = pageWindow(17, 35, 10);
    expect(pages[0]).toBe(0);
    expect(pages.at(-1)).toBe(34);
    expect(pages).toContain(17);
    expect(pages).toContain('ellipsis-start');
    expect(pages).toContain('ellipsis-end');
    expect(pages.filter((page) => typeof page === 'number')).toHaveLength(10);
  });

  it.each([0, 1, 17, 33, 34])('bounds the moving window at page %s', (current) => {
    const pages = pageWindow(current, 35, 6);
    const numbers = pages.filter((page) => typeof page === 'number');
    expect(numbers).toHaveLength(6);
    expect(numbers).toContain(current);
    expect(new Set(pages).size).toBe(pages.length);
    expect(numbers).toEqual([...numbers].sort((a, b) => a - b));
  });

  it('shows the final pages without gaps at the end', () => {
    expect(pageWindow(34, 35, 6)).toEqual([0, 'ellipsis-start', 30, 31, 32, 33, 34]);
  });

  it('returns an empty window when there are no pages', () => {
    expect(pageWindow(0, 0, 6)).toEqual([]);
  });
});
