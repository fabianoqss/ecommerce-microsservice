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

  it('accumulates quantities and persists changes', () => {
    expect(cart.add('p1')).toBe('added');
    expect(cart.add('p1')).toBe('added');
    expect(cart.items()).toEqual([{ productId: 'p1', quantity: 2 }]);
    expect(cart.totalQuantity()).toBe(2);
    expect(cart.distinctCount()).toBe(1);
    expect(JSON.parse(localStorage.getItem('cart:ana') ?? 'null')).toEqual([
      { productId: 'p1', quantity: 2 },
    ]);
  });

  it('rejects product 51 without changing the existing fifty', () => {
    for (let i = 0; i < MAX_CART_PRODUCTS; i++) cart.add('p' + i);
    const before = cart.itemsSnapshotKey();
    expect(cart.add('extra')).toBe('limit-products');
    expect(cart.distinctCount()).toBe(50);
    expect(cart.itemsSnapshotKey()).toBe(before);
    expect(cart.add('p0')).toBe('added');
    expect(cart.totalQuantity()).toBe(51);
  });

  it('rejects quantities above 10000 without changing the cart', () => {
    cart.add('p1', MAX_QUANTITY_PER_PRODUCT);
    expect(cart.add('p1')).toBe('limit-quantity');
    expect(cart.add('p2', 10001)).toBe('limit-quantity');
    expect(cart.totalQuantity()).toBe(10000);
  });

  it.each([0, -1, 1.5, NaN, Infinity])('rejects invalid quantity when adding: %s', (quantity) => {
    expect(cart.add('p1', quantity)).toBe('limit-quantity');
    expect(cart.items()).toEqual([]);
  });

  it('updates valid quantities, ignores invalid ones and removes items at zero', () => {
    cart.add('p1');
    cart.setQuantity('p1', 3);
    expect(cart.totalQuantity()).toBe(3);
    for (const quantity of [-1, 1.5, NaN, Infinity, 10001]) cart.setQuantity('p1', quantity);
    cart.setQuantity('missing', 2);
    expect(cart.items()).toEqual([{ productId: 'p1', quantity: 3 }]);
    cart.setQuantity('p1', 0);
    expect(cart.items()).toEqual([]);
    expect(localStorage.getItem('cart:ana')).toBe('[]');
  });

  it('removes an item and clears the current account cart', () => {
    cart.add('p1');
    cart.add('p2', 2);
    cart.remove('p1');
    expect(cart.items()).toEqual([{ productId: 'p2', quantity: 2 }]);
    cart.clear();
    expect(cart.totalQuantity()).toBe(0);
    expect(localStorage.getItem('cart:ana')).toBe('[]');
  });

  it('subtracts ordered quantities from the current cart and persists remaining items', () => {
    cart.add('p1', 5);
    cart.add('p2', 4);
    const before = cart.items();
    const ordered = Object.freeze([{ productId: 'p1', quantity: 2 }]);
    cart.removeOrdered(ordered);
    expect(cart.items()).toEqual([
      { productId: 'p1', quantity: 3 },
      { productId: 'p2', quantity: 4 },
    ]);
    expect(cart.totalQuantity()).toBe(7);
    expect(JSON.parse(localStorage.getItem('cart:ana') ?? 'null')).toEqual([
      { productId: 'p1', quantity: 3 },
      { productId: 'p2', quantity: 4 },
    ]);
    expect(ordered).toEqual([{ productId: 'p1', quantity: 2 }]);
    expect(before).toEqual([
      { productId: 'p1', quantity: 5 },
      { productId: 'p2', quantity: 4 },
    ]);
  });

  it('removes items with zero or negative balances and does not recreate removed items', () => {
    cart.add('p1', 2);
    cart.add('p2');
    cart.add('p3', 4);
    cart.removeOrdered([
      { productId: 'p1', quantity: 2 },
      { productId: 'p2', quantity: 3 },
      { productId: 'missing', quantity: 1 },
    ]);
    expect(cart.items()).toEqual([{ productId: 'p3', quantity: 4 }]);
    expect(cart.totalQuantity()).toBe(4);
    expect(cart.distinctCount()).toBe(1);
  });

  it('preserves the stored cart when receiving ordered items without an authenticated user', () => {
    cart.add('p1', 2);
    user.set(null);
    cart.removeOrdered([{ productId: 'p1', quantity: 2 }]);
    expect(cart.items()).toEqual([]);
    expect(localStorage.getItem('cart:ana')).toBe('[{"productId":"p1","quantity":2}]');
  });

  it('isolates accounts immediately and preserves the cart on logout', () => {
    cart.add('p1');
    user.set(null);
    expect(cart.items()).toEqual([]);
    cart.add('ignored');
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

  it('reloads only external changes for the current account', () => {
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

  it('generates a stable key from sorted contents without reordering items', () => {
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
