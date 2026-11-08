import { Observable } from 'rxjs';
import { CartItem } from './cart-item';

export abstract class CartStore {
  abstract load(userId: string): CartItem[];
  abstract save(userId: string, items: CartItem[]): void;
  abstract changes(): Observable<string>;
}
