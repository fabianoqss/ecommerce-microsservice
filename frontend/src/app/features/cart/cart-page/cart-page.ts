import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { Cart } from '../../../core/cart/cart';
import { problemDetail } from '../../../core/http/problem-detail';
import { loadCartView } from '../../../shared/data-access/cart-view/cart-view';
import { priceParts } from '../../../shared/format/price-parts';
import { CartLine } from '../cart-line/cart-line';

@Component({
  selector: 'app-cart-page',
  imports: [RouterLink, Button, Message, Skeleton, CartLine],
  templateUrl: './cart-page.html',
})
export class CartPage {
  private readonly router = inject(Router);
  protected readonly cart = inject(Cart);
  protected readonly view = loadCartView(this.cart.items);
  protected readonly subtotal = computed(() => priceParts(this.view.subtotal()));
  protected readonly errorMessage = computed(() =>
    problemDetail(this.view.error(), 'Não foi possível carregar o carrinho. Tente novamente.'),
  );

  protected continueToCheckout(): void {
    if (this.view.canCheckout()) void this.router.navigateByUrl('/checkout');
  }
}
