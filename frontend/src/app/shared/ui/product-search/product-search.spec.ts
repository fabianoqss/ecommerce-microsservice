import { TestBed } from '@angular/core/testing';
import { ProductSearch } from './product-search';

describe('ProductSearch', () => {
  async function render() {
    const fixture = TestBed.createComponent(ProductSearch);
    fixture.componentRef.setInput('categories', ['Accessories', 'Peripherals']);
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => vi.useRealTimers());

  it('emits the trimmed name only after 300 ms', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    fixture.componentInstance.nameChange.subscribe(changed);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    expect(field?.maxLength).toBe(100);
    if (field) {
      field.value = ' Keyboard ';
      field.dispatchEvent(new Event('input'));
      await vi.advanceTimersByTimeAsync(299);
      expect(changed).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(1);
      expect(changed).toHaveBeenCalledExactlyOnceWith('Keyboard');
    }
  });

  it('emits the selected category', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    fixture.componentInstance.categoryChange.subscribe(changed);
    const select = element.querySelector('select');
    expect(select).not.toBeNull();
    if (select) {
      select.value = 'Peripherals';
      select.dispatchEvent(new Event('change'));
      expect(changed).toHaveBeenCalledExactlyOnceWith('Peripherals');
    }
  });

  it('cancels pending typing when clearing filters', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    const cleared = vi.fn();
    fixture.componentInstance.nameChange.subscribe(changed);
    fixture.componentInstance.clear.subscribe(cleared);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    if (field) {
      field.value = 'Keyboard';
      field.dispatchEvent(new Event('input'));
    }
    element.querySelector('button')?.click();
    await vi.advanceTimersByTimeAsync(300);
    expect(cleared).toHaveBeenCalledOnce();
    expect(changed).not.toHaveBeenCalled();
    expect(field?.value).toBe('');
  });

  it('synchronizes URL values and cancels the previous debounce', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    fixture.componentInstance.nameChange.subscribe(changed);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    if (field) {
      field.value = 'Pending';
      field.dispatchEvent(new Event('input'));
    }
    fixture.componentRef.setInput('name', 'Monitor');
    fixture.componentRef.setInput('category', 'Peripherals');
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(300);
    expect(field?.value).toBe('Monitor');
    expect(element.querySelector('select')?.value).toBe('Peripherals');
    expect(changed).not.toHaveBeenCalled();
  });

  it('disables unavailable categories without blocking name search', async () => {
    const { fixture, element } = await render();
    fixture.componentRef.setInput('categories', []);
    fixture.detectChanges();
    expect(element.querySelector('select')?.disabled).toBe(true);
    expect(element.querySelector('input')?.disabled).toBe(false);
  });

  it('preserves pending typing when the category changes', async () => {
    const { fixture, element } = await render();
    const changed = vi.fn();
    fixture.componentInstance.nameChange.subscribe(changed);
    vi.useFakeTimers();
    const field = element.querySelector('input');
    if (!field) {
      throw new Error('Name field missing');
    }
    field.value = ' Keyboard ';
    field.dispatchEvent(new Event('input'));
    fixture.componentRef.setInput('category', 'Peripherals');
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(300);
    expect(field.value).toBe(' Keyboard ');
    expect(changed).toHaveBeenCalledExactlyOnceWith('Keyboard');
  });
});
