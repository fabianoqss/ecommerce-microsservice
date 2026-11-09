import { Product } from '../product/product';

export interface CartLineView {
  productId: string;
  quantity: number;
  product: Product | null;
  stock: number | null;
  status: 'ok' | 'unavailable' | 'out-of-stock' | 'insufficient-stock' | 'error';
}
