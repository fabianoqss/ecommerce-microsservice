import { HttpErrorResponse } from '@angular/common/http';
import { computed, DestroyRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { CartItem } from '../../../core/cart/cart-item';
import { InventoryItem } from '../inventory/inventory';
import { InventoryApi } from '../inventory/inventory-api';
import { Product } from '../product/product';
import { ProductApi } from '../product/product-api';
import { CartLineView } from './cart-line-view';

type Result<T> = { value: T } | { error: unknown };

export function loadCartView(items: () => CartItem[]) {
  const api = inject(ProductApi);
  const inventory = inject(InventoryApi);
  const destroyRef = inject(DestroyRef);
  const products = signal(new Map<string, Result<Product | 'not-found'>>());
  const stocks = signal(new Map<string, Result<InventoryItem | null>>());
  const loadingProducts = signal(new Set<string>());
  const loadingStocks = signal(new Set<string>());
  const initialLoadComplete = signal(false);
  const pending = computed(() =>
    items().some(
      ({ productId }) =>
        !products().has(productId) ||
        !stocks().has(productId) ||
        loadingProducts().has(productId) ||
        loadingStocks().has(productId),
    ),
  );

  function fetchProduct(id: string): void {
    loadingProducts.update((current) => new Set(current).add(id));
    api
      .getById(id)
      .pipe(
        takeUntilDestroyed(destroyRef),
        finalize(() => {
          loadingProducts.update((current) => {
            const next = new Set(current);
            next.delete(id);
            return next;
          });
        }),
      )
      .subscribe({
        next: (product) =>
          products.update((current) => new Map(current).set(id, { value: product })),
        error: (error: unknown) =>
          products.update((current) =>
            new Map(current).set(
              id,
              error instanceof HttpErrorResponse && error.status === 404
                ? { value: 'not-found' }
                : { error },
            ),
          ),
      });
  }

  function fetchStock(ids: string[]): void {
    if (ids.length === 0) return;
    loadingStocks.update((current) => new Set([...current, ...ids]));
    inventory
      .getBySkus(ids)
      .pipe(
        takeUntilDestroyed(destroyRef),
        finalize(() => {
          loadingStocks.update((current) => {
            const next = new Set(current);
            ids.forEach((id) => next.delete(id));
            return next;
          });
        }),
      )
      .subscribe({
        next: (response) => {
          const bySku = new Map(response.map((stock) => [stock.skuCode, stock]));
          stocks.update((current) => {
            const next = new Map(current);
            ids.forEach((id) => next.set(id, { value: bySku.get(id) ?? null }));
            return next;
          });
        },
        error: (error: unknown) =>
          stocks.update((current) => {
            const next = new Map(current);
            ids.forEach((id) => next.set(id, { error }));
            return next;
          }),
      });
  }

  effect(() => {
    const ids = [...new Set(items().map((item) => item.productId))].sort();
    const missingProducts = ids.filter((id) => !products().has(id) && !loadingProducts().has(id));
    const missingStocks = ids.filter((id) => !stocks().has(id) && !loadingStocks().has(id));
    missingProducts.forEach(fetchProduct);
    fetchStock(missingStocks);
    if (ids.length > 0 && !pending()) initialLoadComplete.set(true);
  });

  const isLoading = computed(() => !initialLoadComplete() && pending());
  const error = computed(() => {
    if (pending()) return undefined;
    for (const { productId } of items()) {
      const product = products().get(productId);
      const stock = stocks().get(productId);
      if (product && 'error' in product) return product.error;
      if (stock && 'error' in stock) return stock.error;
    }
    return undefined;
  });
  const lines = computed<CartLineView[]>(() =>
    items().flatMap((item): CartLineView[] => {
      const productResult = products().get(item.productId);
      const stockResult = stocks().get(item.productId);
      if (!productResult || !stockResult) return [];
      const product =
        'value' in productResult && productResult.value !== 'not-found'
          ? productResult.value
          : null;
      const inventoryItem = 'value' in stockResult ? stockResult.value : null;
      const stock = inventoryItem?.quantity ?? null;
      const status =
        'error' in productResult || 'error' in stockResult
          ? 'error'
          : !product
            ? 'unavailable'
            : !inventoryItem?.inStock || stock === null || stock < 1
              ? 'out-of-stock'
              : item.quantity > stock
                ? 'insufficient-stock'
                : 'ok';
      return [{ ...item, product, stock, status }];
    }),
  );
  const subtotal = computed(() =>
    lines().reduce(
      (total, line) =>
        line.status === 'ok' && line.product ? total + line.product.price * line.quantity : total,
      0,
    ),
  );
  const canCheckout = computed(
    () => !pending() && lines().length > 0 && lines().every((line) => line.status === 'ok'),
  );

  return {
    lines,
    subtotal,
    isLoading,
    error,
    canCheckout,
    reload(): void {
      const ids = [...new Set(items().map((item) => item.productId))].sort();
      ids
        .filter((id) => {
          const result = products().get(id);
          return result && 'error' in result && !loadingProducts().has(id);
        })
        .forEach(fetchProduct);
      fetchStock(
        ids.filter((id) => {
          const result = stocks().get(id);
          return result && 'error' in result && !loadingStocks().has(id);
        }),
      );
    },
  };
}
