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

  it('identifica a página atual e desabilita a seta anterior na primeira página', async () => {
    const { element } = await render();
    expect(element.querySelector('nav')?.getAttribute('aria-label')).toBe('Paginação');
    expect(element.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('1');
    expect(
      element.querySelector<HTMLButtonElement>('[aria-label="Página anterior"]')?.disabled,
    ).toBe(true);
    expect(
      element.querySelector<HTMLButtonElement>('[aria-label="Próxima página"]')?.disabled,
    ).toBe(false);
  });

  it('emite a página zero-based ao selecionar um número ou seta', async () => {
    const { fixture, element } = await render(1);
    const changed = vi.fn();
    fixture.componentInstance.pageChange.subscribe(changed);
    element.querySelector<HTMLButtonElement>('[aria-label="Página 3"]')?.click();
    expect(changed).toHaveBeenLastCalledWith(2);
    element.querySelector<HTMLButtonElement>('[aria-label="Página anterior"]')?.click();
    expect(changed).toHaveBeenLastCalledWith(0);
  });

  it('desabilita a próxima seta na última página', async () => {
    const { element } = await render(2);
    expect(
      element.querySelector<HTMLButtonElement>('[aria-label="Próxima página"]')?.disabled,
    ).toBe(true);
  });

  it.each([0, 1])('esconde navegação com %s páginas', async (totalPages) => {
    const { element } = await render(0, totalPages);
    expect(element.querySelector('nav')).toBeNull();
  });
});
