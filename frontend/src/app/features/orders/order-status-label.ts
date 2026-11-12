import { OrderStatus } from '../../shared/data-access/order/order';

const labels: Record<OrderStatus, string> = {
  PENDING: 'Processing',
  CONFIRMED: 'Confirmed',
  EXPIRED: 'Expired',
  RECONCILIATION_FAILED: 'Under review',
  CANCELLED: 'Canceled',
  COMPLETED: 'Completed',
};

export function orderStatusLabel(status: OrderStatus): string {
  return labels[status];
}
