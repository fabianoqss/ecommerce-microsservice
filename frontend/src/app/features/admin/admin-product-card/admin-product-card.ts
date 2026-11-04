import { Component, computed, input, output } from '@angular/core';
import { Product } from '../../../shared/data-access/product/product';
import { priceParts } from '../../../shared/format/price-parts';

@Component({
  selector: 'app-admin-product-card',
  templateUrl: './admin-product-card.html',
})
export class AdminProductCard {
  readonly product = input.required<Product>();
  readonly edit = output<Product>();
  readonly remove = output<Product>();
  protected readonly price = computed(() => priceParts(this.product().price));
}
