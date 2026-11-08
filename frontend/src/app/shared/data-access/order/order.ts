export type OrderStatus =
  'PENDING' | 'CONFIRMED' | 'EXPIRED' | 'RECONCILIATION_FAILED' | 'CANCELLED' | 'COMPLETED';

export interface OrderItem {
  productId: string;
  productName: string;
  quantity: number;
  priceAtPurchase: number;
}

export interface Order {
  id: string;
  status: OrderStatus;
  totalValue: number;
  orderTime: string;
  items: OrderItem[];
}
