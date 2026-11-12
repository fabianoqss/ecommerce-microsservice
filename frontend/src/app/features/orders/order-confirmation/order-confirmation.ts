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

const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'short',
});

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
  protected readonly formattedOrderTime = computed(() => {
    const orderTime = this.order()?.orderTime;
    return orderTime ? dateTimeFormatter.format(new Date(orderTime)) : '';
  });
  protected readonly statusLabel = orderStatusLabel;
  protected readonly notFound = computed(() => {
    const error = this.orderResource.error();
    return !this.validId() || (error instanceof HttpErrorResponse && error.status === 404);
  });
  protected readonly errorMessage = computed(() =>
    problemDetail(this.orderResource.error(), 'Unable to load the order. Please try again.'),
  );
}
