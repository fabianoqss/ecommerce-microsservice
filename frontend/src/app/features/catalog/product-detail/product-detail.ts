import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { Button } from 'primeng/button';
import { MessageService } from 'primeng/api';
import { AuthService } from '../../../core/auth/auth.service';
import { Cart } from '../../../core/cart/cart';
import { problemDetail } from '../../../core/http/problem-detail';
import { InventoryApi } from '../../../shared/data-access/inventory/inventory-api';
import { priceParts } from '../../../shared/format/price-parts';
import { ProductApi } from '../../../shared/data-access/product/product-api';

@Component({
  imports: [RouterLink, Message, Skeleton, Button],
  selector: 'app-product-detail',
  templateUrl: './product-detail.html',
})
export class ProductDetail {
  readonly id = input.required<string>();
  private readonly title = inject(Title);
  private readonly auth = inject(AuthService);
  private readonly cart = inject(Cart);
  private readonly messages = inject(MessageService);
  protected readonly inventoryResource = inject(InventoryApi).bySkus(() => [this.id()]);
  protected readonly inventory = computed(() =>
    this.inventoryResource.hasValue()
      ? this.inventoryResource.value().find((item) => item.skuCode === this.id())
      : undefined,
  );
  protected readonly canAdd = computed(
    () =>
      !this.inventoryResource.isLoading() &&
      !this.inventoryResource.error() &&
      this.inventory()?.inStock === true &&
      (this.inventory()?.quantity ?? 0) > 0,
  );
  protected readonly stockLabel = computed(() => {
    if (this.inventoryResource.isLoading()) return 'Checking stock';
    if (!this.inventoryResource.hasValue()) return 'Add to cart';
    const stock = this.inventory();
    return !stock || !stock.inStock || stock.quantity < 1 ? 'Out of stock' : 'Add to cart';
  });
  protected readonly inventoryErrorMessage = computed(() =>
    problemDetail(this.inventoryResource.error(), 'Unable to check stock for this product.'),
  );
  protected readonly productResource = inject(ProductApi).byId(this.id);
  protected readonly product = computed(() =>
    this.productResource.hasValue() ? this.productResource.value() : undefined,
  );
  protected readonly price = computed(() => priceParts(this.product()?.price ?? 0));
  protected readonly notFound = computed(() => {
    const error = this.productResource.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });
  protected readonly errorMessage = computed(() =>
    problemDetail(this.productResource.error(), 'Unable to load the product. Please try again.'),
  );

  protected addToCart(): void {
    if (!this.auth.isAuthenticated()) {
      this.auth.login();
      return;
    }
    const product = this.product();
    const stock = this.inventory();
    if (!product || !stock || !this.canAdd()) return;
    const inCart = this.cart.items().find((item) => item.productId === product.id)?.quantity ?? 0;
    if (inCart >= stock.quantity) {
      this.messages.add({
        key: 'cart',
        severity: 'error',
        detail: `Only ${stock.quantity} in stock. You already have ${inCart} in your cart.`,
      });
      return;
    }
    const result = this.cart.add(product.id);
    this.messages.add({
      key: 'cart',
      severity: result === 'added' ? 'success' : 'error',
      detail:
        result === 'added'
          ? 'Product added to cart'
          : result === 'limit-products'
            ? 'Your cart can contain up to 50 different products.'
            : 'You can add up to 10,000 units of each product.',
    });
  }

  constructor() {
    effect(() => {
      const product = this.product();
      this.title.setTitle(
        product && !this.productResource.isLoading()
          ? product.name + ' | DS Catalog'
          : 'Product | DS Catalog',
      );
    });
  }
}
