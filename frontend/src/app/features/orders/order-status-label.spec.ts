import { OrderStatus } from '../../shared/data-access/order/order';
import { orderStatusLabel } from './order-status-label';

describe('orderStatusLabel', () => {
  it.each<[OrderStatus, string]>([
    ['PENDING', 'Processing'],
    ['CONFIRMED', 'Confirmed'],
    ['EXPIRED', 'Expired'],
    ['RECONCILIATION_FAILED', 'Under review'],
    ['CANCELLED', 'Canceled'],
    ['COMPLETED', 'Completed'],
  ])('translates status %s to %s', (status, label) => {
    expect(orderStatusLabel(status)).toBe(label);
  });
});
