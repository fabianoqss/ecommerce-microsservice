import { TestBed } from '@angular/core/testing';
import { LocalStorageCartStore } from './local-storage-cart-store';

describe('LocalStorageCartStore', () => {
  let store: LocalStorageCartStore;

  beforeEach(() => {
    localStorage.clear();
    store = TestBed.inject(LocalStorageCartStore);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('persiste apenas ids e quantidades sob a chave da conta', () => {
    store.save('ana', [{ productId: 'p1', quantity: 2 }]);
    expect(localStorage.getItem('cart:ana')).toBe('[{"productId":"p1","quantity":2}]');
    expect(store.load('ana')).toEqual([{ productId: 'p1', quantity: 2 }]);
    expect(store.load('bia')).toEqual([]);
  });

  it.each([null, 'abc', '"abc"', '{}', 'null', '42'])(
    'ignora armazenamento ausente ou inválido: %s',
    (value) => {
      if (value !== null) localStorage.setItem('cart:ana', value);
      expect(store.load('ana')).toEqual([]);
    },
  );

  it('descarta entradas inválidas e duplicatas sem perder entradas válidas', () => {
    localStorage.setItem(
      'cart:ana',
      JSON.stringify([
        null,
        {},
        { productId: 1, quantity: 1 },
        { productId: '', quantity: 1 },
        { productId: '  ', quantity: 1 },
        { productId: 'p1', quantity: -1 },
        { productId: 'p1', quantity: 0 },
        { productId: 'p1', quantity: 1.5 },
        { productId: 'p1', quantity: '2' },
        { productId: 'p1', quantity: 10001 },
        { productId: 'p1', quantity: 2, price: 100, name: 'Privado' },
        { productId: 'p1', quantity: 3 },
        { productId: 'p2', quantity: 10000 },
      ]),
    );
    expect(store.load('ana')).toEqual([
      { productId: 'p1', quantity: 2 },
      { productId: 'p2', quantity: 10000 },
    ]);
  });

  it('limita o carrinho a cinquenta produtos distintos', () => {
    localStorage.setItem(
      'cart:ana',
      JSON.stringify(
        Array.from({ length: 51 }, (_, index) => ({ productId: 'p' + index, quantity: 1 })),
      ),
    );
    expect(store.load('ana')).toHaveLength(50);
    expect(store.load('ana').at(-1)).toEqual({ productId: 'p49', quantity: 1 });
  });

  it('não propaga erros de acesso, leitura ou escrita do storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('Bloqueado');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('Sem espaço');
    });
    expect(store.load('ana')).toEqual([]);
    expect(() => store.save('ana', [{ productId: 'p1', quantity: 1 }])).not.toThrow();
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new Error('Sem acesso');
    });
    expect(store.load('ana')).toEqual([]);
    expect(() => store.save('ana', [])).not.toThrow();
  });

  it('emite a conta alterada em outra aba e ignora outras chaves e sessionStorage', () => {
    const changed = vi.fn();
    const subscription = store.changes().subscribe(changed);
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'cart:ana', storageArea: localStorage }),
    );
    window.dispatchEvent(new StorageEvent('storage', { key: 'outro', storageArea: localStorage }));
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'cart:bia', storageArea: sessionStorage }),
    );
    expect(changed).toHaveBeenCalledExactlyOnceWith('ana');
    subscription.unsubscribe();
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'cart:ana', storageArea: localStorage }),
    );
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it('notifica contas carregadas quando outra aba limpa todo o storage', () => {
    store.load('ana');
    const changed = vi.fn();
    const subscription = store.changes().subscribe(changed);
    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }));
    expect(changed).toHaveBeenCalledExactlyOnceWith('ana');
    subscription.unsubscribe();
  });
});
