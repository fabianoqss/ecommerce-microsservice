import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { problemDetail } from '../../../core/http/problem-detail';
import { OrderApi } from '../../../shared/data-access/order/order-api';
import { priceParts } from '../../../shared/format/price-parts';
import { orderStatusLabel } from '../order-status-label';

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

@Component({
  imports: [RouterLink, Button, Message, Skeleton],
  selector: 'app-order-confirmation',
  templateUrl: './order-confirmation.html',
})
export class OrderConfirmation {
  readonly id = input.required<string>();
  private readonly validId = computed(() => uuidPattern.test(this.id()));
  protected readonly orderResource = inject(OrderApi).byId(() => (this.validId() ? this.id() : ''));
  protected readonly order = computed(() =>
    this.orderResource.hasValue() ? this.orderResource.value() : undefined,
  );
  protected readonly lines = computed(() =>
    (this.order()?.items ?? []).map((item) => ({
      ...item,
      unitPrice: priceParts(item.priceAtPurchase),
    })),
  );
  protected readonly total = computed(() => priceParts(this.order()?.totalValue ?? 0));
  protected readonly formattedOrderTime = computed(
    () =>
      this.order()?.orderTime.replace(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}:\d{2}).*$/, '$3/$2/$1 $4') ??
      '',
  );
  protected readonly statusLabel = orderStatusLabel;
  protected readonly notFound = computed(() => {
    const error = this.orderResource.error();
    return !this.validId() || (error instanceof HttpErrorResponse && error.status === 404);
  });
  protected readonly errorMessage = computed(() =>
    problemDetail(
      this.orderResource.error(),
      'Não foi possível carregar o pedido. Tente novamente.',
    ),
  );
}
