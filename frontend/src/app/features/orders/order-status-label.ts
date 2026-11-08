import { OrderStatus } from '../../shared/data-access/order/order';

const labels: Record<OrderStatus, string> = {
  PENDING: 'Processando',
  CONFIRMED: 'Confirmado',
  EXPIRED: 'Expirado',
  RECONCILIATION_FAILED: 'Em análise',
  CANCELLED: 'Cancelado',
  COMPLETED: 'Concluído',
};

export function orderStatusLabel(status: OrderStatus): string {
  return labels[status];
}
