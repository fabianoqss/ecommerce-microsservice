import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { finalize } from 'rxjs';
import { Cart } from '../../../core/cart/cart';
import { problemDetail } from '../../../core/http/problem-detail';
import { loadCartView } from '../../../shared/data-access/cart-view/cart-view';
import { OrderApi } from '../../../shared/data-access/order/order-api';
import { priceParts } from '../../../shared/format/price-parts';
import { CheckoutAttempt } from './checkout-attempt';

@Component({
  imports: [RouterLink, Button, Message, Skeleton],
  selector: 'app-checkout',
  templateUrl: './checkout.html',
})
export class Checkout {
  private readonly router = inject(Router);
  private readonly orderApi = inject(OrderApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly attempt = inject(CheckoutAttempt);
  protected readonly cart = inject(Cart);
  protected readonly view = loadCartView(this.cart.items);
  protected readonly submitting = signal(false);
  protected readonly placed = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly total = computed(() => priceParts(this.view.subtotal()));
  protected readonly lines = computed(() =>
    this.view.lines().map((line) => ({
      ...line,
      name:
        line.product?.name ??
        `${line.status === 'unavailable' ? 'Product unavailable' : 'Product'} (${line.productId})`,
      unitPrice: priceParts(line.product?.price ?? 0),
      total: priceParts((line.product?.price ?? 0) * line.quantity),
    })),
  );
  protected readonly loadError = computed(() =>
    problemDetail(this.view.error(), 'Unable to load the order. Please try again.'),
  );
  private alive = true;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.alive = false;
    });
  }

  protected submit(): void {
    if (this.submitting() || this.placed() || !this.view.canCheckout()) return;
    this.submitting.set(true);
    this.submitError.set(null);
    const items = this.cart.items().map(({ productId, quantity }) => ({ productId, quantity }));
    this.orderApi
      .create({ items }, this.attempt.keyFor(this.cart.itemsSnapshotKey()))
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (order) => {
          this.placed.set(true);
          this.cart.removeOrdered(items);
          this.attempt.complete();
          if (this.alive) {
            void this.router.navigate(['/orders', order.id, 'confirmation']);
          }
        },
        error: (error: unknown) => {
          if (error instanceof HttpErrorResponse && error.status === 422) {
            this.attempt.renew();
            this.submitError.set('Your cart has changed. Review it and try again.');
            return;
          }
          this.submitError.set(
            problemDetail(error, 'Unable to place your order. Please try again.'),
          );
        },
      });
  }
}
