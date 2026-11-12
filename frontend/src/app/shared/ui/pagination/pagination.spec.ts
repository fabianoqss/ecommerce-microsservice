import { TestBed } from '@angular/core/testing';
import { Pagination } from './pagination';

describe('Pagination', () => {
  async function render(page = 0, totalPages = 3) {
    const fixture = TestBed.createComponent(Pagination);
    fixture.componentRef.setInput('page', page);
    fixture.componentRef.setInput('totalPages', totalPages);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('identifies the current page and disables the previous arrow on the first page', async () => {
    const { element } = await render();
    expect(element.querySelector('nav')?.getAttribute('aria-label')).toBe('Pagination');
    expect(element.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('1');
    expect(element.querySelector<HTMLButtonElement>('[aria-label="Previous page"]')?.disabled).toBe(
      true,
    );
    expect(element.querySelector<HTMLButtonElement>('[aria-label="Next page"]')?.disabled).toBe(
      false,
    );
  });

  it('emits the zero-based page when selecting a number or arrow', async () => {
    const { fixture, element } = await render(1);
    const changed = vi.fn();
    fixture.componentInstance.pageChange.subscribe(changed);
    element.querySelector<HTMLButtonElement>('[aria-label="Page 3"]')?.click();
    expect(changed).toHaveBeenLastCalledWith(2);
    element.querySelector<HTMLButtonElement>('[aria-label="Previous page"]')?.click();
    expect(changed).toHaveBeenLastCalledWith(0);
  });

  it('disables the next arrow on the last page', async () => {
    const { element } = await render(2);
    expect(element.querySelector<HTMLButtonElement>('[aria-label="Next page"]')?.disabled).toBe(
      true,
    );
  });

  it.each([0, 1])('hides navigation with %s pages', async (totalPages) => {
    const { element } = await render(0, totalPages);
    expect(element.querySelector('nav')).toBeNull();
  });
});
