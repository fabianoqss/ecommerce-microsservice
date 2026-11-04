import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { priceParts } from '../../../shared/format/price-parts';
import { Product } from '../../../shared/data-access/product/product';

@Component({
  imports: [RouterLink],
  selector: 'app-product-card',
  templateUrl: './product-card.html',
})
export class ProductCard {
  readonly product = input.required<Product>();
  protected readonly price = computed(() => priceParts(this.product().price));
}
