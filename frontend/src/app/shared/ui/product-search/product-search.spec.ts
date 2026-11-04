import { TestBed } from '@angular/core/testing';
import { ProductSearch } from './product-search';

describe('ProductSearch', () => {
  async function render() {
    const fixture = TestBed.createComponent(ProductSearch);
    fixture.componentRef.setInput('categories', ['Acessórios', 'Periféricos']);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => vi.useRealTimers());

  it('emite o nome aparado somente após 300 ms', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    fixture.componentInstance.nameChange.subscribe(changed);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    expect(field?.maxLength).toBe(100);
    if (field) {
      field.value = ' Teclado ';
      field.dispatchEvent(new Event('input'));
      await vi.advanceTimersByTimeAsync(299);
      expect(changed).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(1);
      expect(changed).toHaveBeenCalledExactlyOnceWith('Teclado');
    }
  });

  it('emite a categoria escolhida', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    fixture.componentInstance.categoryChange.subscribe(changed);
    const select = element.querySelector('select');
    expect(select).not.toBeNull();
    if (select) {
      select.value = 'Periféricos';
      select.dispatchEvent(new Event('change'));
      expect(changed).toHaveBeenCalledExactlyOnceWith('Periféricos');
    }
  });

  it('cancela a digitação pendente ao limpar filtros', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    const cleared = vi.fn();
    fixture.componentInstance.nameChange.subscribe(changed);
    fixture.componentInstance.clear.subscribe(cleared);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    if (field) {
      field.value = 'Teclado';
      field.dispatchEvent(new Event('input'));
    }
    element.querySelector('button')?.click();
    await vi.advanceTimersByTimeAsync(300);
    expect(cleared).toHaveBeenCalledOnce();
    expect(changed).not.toHaveBeenCalled();
    expect(field?.value).toBe('');
  });

  it('sincroniza os valores recebidos pela URL e cancela debounce anterior', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    fixture.componentInstance.nameChange.subscribe(changed);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    if (field) {
      field.value = 'Pendente';
      field.dispatchEvent(new Event('input'));
    }
    fixture.componentRef.setInput('name', 'Monitor');
    fixture.componentRef.setInput('category', 'Periféricos');
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(300);
    expect(field?.value).toBe('Monitor');
    expect(element.querySelector('select')?.value).toBe('Periféricos');
    expect(changed).not.toHaveBeenCalled();
  });

  it('desabilita categorias indisponíveis sem bloquear busca por nome', async () => {
    const { fixture, element } = await render();
    fixture.componentRef.setInput('categories', []);
    fixture.detectChanges();
    expect(element.querySelector('select')?.disabled).toBe(true);
    expect(element.querySelector('input')?.disabled).toBe(false);
  });

  it('preserva digitação pendente quando a categoria muda', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    fixture.componentInstance.nameChange.subscribe(changed);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    if (!field) {
      throw new Error('Campo de nome ausente');
    }
    field.value = ' Teclado ';
    field.dispatchEvent(new Event('input'));
    fixture.componentRef.setInput('category', 'Periféricos');
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(300);
    expect(field.value).toBe(' Teclado ');
    expect(changed).toHaveBeenCalledExactlyOnceWith('Teclado');
  });
});
