import { OrderStatus } from '../../shared/data-access/order/order';
import { orderStatusLabel } from './order-status-label';

describe('orderStatusLabel', () => {
  it.each<[OrderStatus, string]>([
    ['PENDING', 'Processando'],
    ['CONFIRMED', 'Confirmado'],
    ['EXPIRED', 'Expirado'],
    ['RECONCILIATION_FAILED', 'Em análise'],
    ['CANCELLED', 'Cancelado'],
    ['COMPLETED', 'Concluído'],
  ])('traduz o status %s para %s', (status, label) => {
    expect(orderStatusLabel(status)).toBe(label);
  });
});
