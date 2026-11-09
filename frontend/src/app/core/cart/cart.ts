import { computed, inject, linkedSignal, Service } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from '../auth/auth.service';
import { CartItem } from './cart-item';
import { CartStore } from './cart-store';

export const MAX_CART_PRODUCTS = 50;
export const MAX_QUANTITY_PER_PRODUCT = 10000;
export type CartAddResult = 'added' | 'limit-products' | 'limit-quantity';

@Service()
export class Cart {
  private readonly store = inject(CartStore);
  private readonly auth = inject(AuthService);
  private readonly userId = computed(() => this.auth.user()?.sub);
  private readonly currentItems = linkedSignal({
    source: this.userId,
    computation: (userId): CartItem[] => (userId ? this.store.load(userId) : []),
  });

  readonly items = this.currentItems.asReadonly();
  readonly totalQuantity = computed(() =>
    this.items().reduce((total, item) => total + item.quantity, 0),
  );
  readonly distinctCount = computed(() => this.items().length);
  readonly itemsSnapshotKey = computed(() =>
    JSON.stringify(
      [...this.items()].sort((a, b) =>
        a.productId < b.productId ? -1 : a.productId > b.productId ? 1 : 0,
      ),
    ),
  );

  constructor() {
    this.store
      .changes()
      .pipe(takeUntilDestroyed())
      .subscribe((userId) => {
        if (userId === this.userId()) {
          this.currentItems.set(this.store.load(userId));
        }
      });
  }

  add(productId: string, quantity = 1): CartAddResult {
    if (!this.userId()) return 'added';
    const items = this.items();
    const existing = items.find((item) => item.productId === productId);
    const total = (existing?.quantity ?? 0) + quantity;
    if (!Number.isInteger(quantity) || quantity < 1 || total > MAX_QUANTITY_PER_PRODUCT) {
      return 'limit-quantity';
    }
    if (!existing && items.length >= MAX_CART_PRODUCTS) return 'limit-products';
    this.update(
      existing
        ? items.map((item) =>
            item.productId === productId ? { productId, quantity: total } : item,
          )
        : [...items, { productId, quantity }],
    );
    return 'added';
  }

  setQuantity(productId: string, quantity: number): void {
    if (quantity === 0) {
      this.remove(productId);
      return;
    }
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > MAX_QUANTITY_PER_PRODUCT) return;
    this.update(
      this.items().map((item) => (item.productId === productId ? { productId, quantity } : item)),
    );
  }

  remove(productId: string): void {
    this.update(this.items().filter((item) => item.productId !== productId));
  }

  clear(): void {
    this.update([]);
  }

  removeOrdered(items: readonly CartItem[]): void {
    const quantities = new Map<string, number>();
    for (const { productId, quantity } of items) {
      quantities.set(productId, (quantities.get(productId) ?? 0) + quantity);
    }
    this.update(
      this.items().flatMap(({ productId, quantity }) => {
        const remaining = quantity - (quantities.get(productId) ?? 0);
        return remaining > 0 ? [{ productId, quantity: remaining }] : [];
      }),
    );
  }

  private update(items: CartItem[]): void {
    const userId = this.userId();
    if (!userId) return;
    this.currentItems.set(items);
    this.store.save(userId, items);
  }
}
