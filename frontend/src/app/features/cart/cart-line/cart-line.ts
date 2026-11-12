import { Component, computed, effect, input, output } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Button } from 'primeng/button';
import { InputNumber } from 'primeng/inputnumber';
import { Message } from 'primeng/message';
import { MAX_QUANTITY_PER_PRODUCT } from '../../../core/cart/cart';
import { CartLineView } from '../../../shared/data-access/cart-view/cart-line-view';
import { priceParts } from '../../../shared/format/price-parts';

@Component({
  selector: 'app-cart-line',
  imports: [ReactiveFormsModule, RouterLink, Button, InputNumber, Message],
  templateUrl: './cart-line.html',
})
export class CartLine {
  readonly line = input.required<CartLineView>();
  readonly quantityChange = output<number>();
  readonly remove = output<void>();
  protected readonly quantity = new FormControl<number | null>(1);
  protected readonly inputId = computed(() => 'quantity-' + this.line().productId);
  protected readonly name = computed(() => this.line().product?.name ?? 'Product unavailable');
  protected readonly articleLabel = computed(
    () => this.line().product?.name ?? `Product unavailable (${this.line().productId})`,
  );
  protected readonly removeLabel = computed(() =>
    this.line().product?.name
      ? 'Remove ' + this.line().product?.name
      : 'Remove unavailable product ' + this.line().productId,
  );
  protected readonly maxQuantity = computed(() =>
    this.line().status === 'ok'
      ? Math.min(this.line().stock ?? 0, MAX_QUANTITY_PER_PRODUCT)
      : Math.max(this.line().quantity, Math.min(this.line().stock ?? 0, MAX_QUANTITY_PER_PRODUCT)),
  );
  protected readonly unitPrice = computed(() => priceParts(this.line().product?.price ?? 0));
  protected readonly total = computed(() =>
    priceParts((this.line().product?.price ?? 0) * this.line().quantity),
  );

  private draftQuantity: number | null = null;

  constructor() {
    effect(() => {
      this.draftQuantity = this.line().quantity;
      this.quantity.setValue(this.line().quantity, { emitEvent: false });
      if (this.line().status !== 'ok' && this.line().status !== 'insufficient-stock') {
        this.quantity.disable({ emitEvent: false });
      } else {
        this.quantity.enable({ emitEvent: false });
      }
    });
  }

  protected updateQuantity(value: number | string | null): void {
    const quantity = typeof value === 'string' ? Number(value) : value;
    this.draftQuantity = quantity;
    if (this.isValidQuantity(quantity) && quantity !== this.line().quantity) {
      this.quantityChange.emit(quantity);
    }
  }

  protected onQuantityInput(event: Event): void {
    if (event.target instanceof HTMLInputElement) {
      this.updateQuantity(event.target.value.trim() === '' ? null : event.target.value);
    }
  }

  protected onQuantityKeydown(event: KeyboardEvent): void {
    if (event.key === 'Home' || event.key === 'End') {
      this.updateQuantity(this.quantity.value);
    }
  }

  protected restoreQuantity(event: Event): void {
    if (!this.isValidQuantity(this.draftQuantity)) {
      this.draftQuantity = this.line().quantity;
      this.quantity.setValue(this.line().quantity, { emitEvent: false });
      if (event.target instanceof HTMLInputElement) {
        const value = String(this.line().quantity);
        event.target.value = value;
        event.target.setAttribute('aria-valuenow', value);
      }
    }
  }

  private isValidQuantity(quantity: number | null): quantity is number {
    return (
      (this.line().status === 'ok' || this.line().status === 'insufficient-stock') &&
      quantity !== null &&
      Number.isInteger(quantity) &&
      quantity >= 1 &&
      quantity <= MAX_QUANTITY_PER_PRODUCT &&
      (quantity <= (this.line().stock ?? 0) || quantity < this.line().quantity)
    );
  }
}
