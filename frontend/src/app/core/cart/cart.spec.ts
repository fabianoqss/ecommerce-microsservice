import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { AuthUser } from '../auth/auth-user';
import { AuthService } from '../auth/auth.service';
import { Cart, MAX_CART_PRODUCTS, MAX_QUANTITY_PER_PRODUCT } from './cart';
import { CartStore } from './cart-store';
import { LocalStorageCartStore } from './local-storage-cart-store';

describe('Cart', () => {
  const ana: AuthUser = { sub: 'ana', name: 'Ana', email: 'ana@example.com', roles: ['CUSTOMER'] };
  const bia: AuthUser = { ...ana, sub: 'bia' };
  let user: ReturnType<typeof signal<AuthUser | null>>;
  let changes: Subject<string>;
  let cart: Cart;

  beforeEach(() => {
    localStorage.clear();
    user = signal<AuthUser | null>(ana);
    changes = new Subject<string>();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: { user, isAuthenticated: computed(() => user() !== null) },
        },
        { provide: CartStore, useClass: LocalStorageCartStore },
      ],
    });
    vi.spyOn(TestBed.inject(CartStore), 'changes').mockReturnValue(changes);
    cart = TestBed.inject(Cart);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('acumula quantidades e persiste as alterações', () => {
    expect(cart.add('p1')).toBe('added');
    expect(cart.add('p1')).toBe('added');
    expect(cart.items()).toEqual([{ productId: 'p1', quantity: 2 }]);
    expect(cart.totalQuantity()).toBe(2);
    expect(cart.distinctCount()).toBe(1);
    expect(JSON.parse(localStorage.getItem('cart:ana') ?? 'null')).toEqual([
      { productId: 'p1', quantity: 2 },
    ]);
  });

  it('recusa o produto 51 sem alterar os cinquenta existentes', () => {
    for (let i = 0; i < MAX_CART_PRODUCTS; i++) cart.add('p' + i);
    const before = cart.itemsSnapshotKey();
    expect(cart.add('extra')).toBe('limit-products');
    expect(cart.distinctCount()).toBe(50);
    expect(cart.itemsSnapshotKey()).toBe(before);
    expect(cart.add('p0')).toBe('added');
    expect(cart.totalQuantity()).toBe(51);
  });

  it('recusa quantidades acima de 10000 sem alterar o carrinho', () => {
    cart.add('p1', MAX_QUANTITY_PER_PRODUCT);
    expect(cart.add('p1')).toBe('limit-quantity');
    expect(cart.add('p2', 10001)).toBe('limit-quantity');
    expect(cart.totalQuantity()).toBe(10000);
  });

  it.each([0, -1, 1.5, NaN, Infinity])(
    'recusa quantidade inválida ao adicionar: %s',
    (quantity) => {
      expect(cart.add('p1', quantity)).toBe('limit-quantity');
      expect(cart.items()).toEqual([]);
    },
  );

  it('altera quantidades válidas, ignora inválidas e remove com zero', () => {
    cart.add('p1');
    cart.setQuantity('p1', 3);
    expect(cart.totalQuantity()).toBe(3);
    for (const quantity of [-1, 1.5, NaN, Infinity, 10001]) cart.setQuantity('p1', quantity);
    cart.setQuantity('ausente', 2);
    expect(cart.items()).toEqual([{ productId: 'p1', quantity: 3 }]);
    cart.setQuantity('p1', 0);
    expect(cart.items()).toEqual([]);
    expect(localStorage.getItem('cart:ana')).toBe('[]');
  });

  it('remove um item e limpa o carrinho da conta atual', () => {
    cart.add('p1');
    cart.add('p2', 2);
    cart.remove('p1');
    expect(cart.items()).toEqual([{ productId: 'p2', quantity: 2 }]);
    cart.clear();
    expect(cart.totalQuantity()).toBe(0);
    expect(localStorage.getItem('cart:ana')).toBe('[]');
  });

  it('isola as contas imediatamente e preserva o carrinho ao sair', () => {
    cart.add('p1');
    user.set(null);
    expect(cart.items()).toEqual([]);
    cart.add('ignorado');
    cart.setQuantity('p1', 2);
    cart.remove('p1');
    cart.clear();
    expect(localStorage.getItem('cart:ana')).toBe('[{"productId":"p1","quantity":1}]');
    user.set(bia);
    expect(cart.items()).toEqual([]);
    cart.add('p2');
    user.set(ana);
    expect(cart.items()).toEqual([{ productId: 'p1', quantity: 1 }]);
    user.set(bia);
    cart.add('p3');
    expect(cart.items()).toEqual([
      { productId: 'p2', quantity: 1 },
      { productId: 'p3', quantity: 1 },
    ]);
  });

  it('recarrega somente alterações externas da conta atual', () => {
    cart.add('p1');
    localStorage.setItem('cart:ana', '[{"productId":"p2","quantity":3}]');
    changes.next('bia');
    expect(cart.items()).toEqual([{ productId: 'p1', quantity: 1 }]);
    changes.next('ana');
    expect(cart.items()).toEqual([{ productId: 'p2', quantity: 3 }]);
    user.set(null);
    changes.next('ana');
    expect(cart.items()).toEqual([]);
  });

  it('gera chave estável por conteúdo ordenado sem reordenar os itens', () => {
    cart.add('b', 2);
    cart.add('a');
    const key = cart.itemsSnapshotKey();
    expect(cart.items().map((item) => item.productId)).toEqual(['b', 'a']);
    cart.clear();
    cart.add('a');
    cart.add('b', 2);
    expect(cart.itemsSnapshotKey()).toBe(key);
    cart.setQuantity('b', 3);
    expect(cart.itemsSnapshotKey()).not.toBe(key);
  });
});
