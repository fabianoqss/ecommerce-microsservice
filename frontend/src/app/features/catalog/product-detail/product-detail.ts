import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { priceParts } from '../../../shared/format/price-parts';
import { ProductApi } from '../data-access/product-api';

@Component({
  imports: [RouterLink, Message, Skeleton],
  selector: 'app-product-detail',
  templateUrl: './product-detail.html',
})
export class ProductDetail {
  readonly id = input.required<string>();
  private readonly title = inject(Title);
  protected readonly productResource = inject(ProductApi).byId(this.id);
  protected readonly product = computed(() =>
    this.productResource.hasValue() ? this.productResource.value() : undefined,
  );
  protected readonly price = computed(() => priceParts(this.product()?.price ?? 0));
  protected readonly notFound = computed(() => {
    const error = this.productResource.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });
  protected readonly errorMessage = computed(() => {
    const error = this.productResource.error();
    const problem: unknown = error instanceof HttpErrorResponse ? error.error : undefined;
    if (
      problem &&
      typeof problem === 'object' &&
      'detail' in problem &&
      typeof problem.detail === 'string' &&
      problem.detail.trim()
    ) {
      return problem.detail;
    }
    return 'Não foi possível carregar o produto. Tente novamente.';
  });

  constructor() {
    effect(() => {
      const product = this.product();
      this.title.setTitle(
        product && !this.productResource.isLoading()
          ? product.name + ' | DS Catalog'
          : 'Produto | DS Catalog',
      );
    });
  }
}
