import { Component, computed, input } from '@angular/core';
import { priceParts } from '../../../shared/format/price-parts';
import { Product } from '../data-access/product';

@Component({
  imports: [],
  selector: 'app-product-card',
  templateUrl: './product-card.html',
})
export class ProductCard {
  readonly product = input.required<Product>();
  protected readonly price = computed(() => priceParts(this.product().price));
}
