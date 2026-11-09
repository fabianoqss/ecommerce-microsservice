export interface CreateOrderRequest {
  items: { productId: string; quantity: number }[];
}
