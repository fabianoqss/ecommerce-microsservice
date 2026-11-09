import { DOCUMENT, inject, Service } from '@angular/core';
import { EMPTY, filter, fromEvent, mergeMap, Observable } from 'rxjs';
import { CartItem } from './cart-item';
import { CartStore } from './cart-store';
import { MAX_CART_PRODUCTS, MAX_QUANTITY_PER_PRODUCT } from './cart';

@Service()
export class LocalStorageCartStore extends CartStore {
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly loadedUsers = new Set<string>();

  load(userId: string): CartItem[] {
    this.loadedUsers.add(userId);
    try {
      const value = this.window?.localStorage.getItem('cart:' + userId);
      return value ? this.validItems(JSON.parse(value)) : [];
    } catch {
      return [];
    }
  }

  save(userId: string, items: CartItem[]): void {
    this.loadedUsers.add(userId);
    try {
      this.window?.localStorage.setItem('cart:' + userId, JSON.stringify(this.validItems(items)));
    } catch {
      return;
    }
  }

  changes(): Observable<string> {
    const window = this.window;
    if (!window) return EMPTY;
    return fromEvent<StorageEvent>(window, 'storage').pipe(
      filter((event) => {
        try {
          return event.storageArea === window.localStorage;
        } catch {
          return false;
        }
      }),
      mergeMap((event) =>
        event.key === null
          ? [...this.loadedUsers]
          : event.key.startsWith('cart:')
            ? [event.key.slice(5)]
            : [],
      ),
    );
  }

  private validItems(value: unknown): CartItem[] {
    if (!Array.isArray(value)) return [];
    const items: CartItem[] = [];
    const ids = new Set<string>();
    for (const entry of value) {
      if (items.length === MAX_CART_PRODUCTS) break;
      const item: unknown = entry;
      if (!item || typeof item !== 'object' || !('productId' in item) || !('quantity' in item))
        continue;
      const { productId, quantity } = item;
      if (
        typeof productId !== 'string' ||
        !productId.trim() ||
        ids.has(productId) ||
        typeof quantity !== 'number' ||
        !Number.isInteger(quantity) ||
        quantity < 1 ||
        quantity > MAX_QUANTITY_PER_PRODUCT
      )
        continue;
      ids.add(productId);
      items.push({ productId, quantity });
    }
    return items;
  }
}
