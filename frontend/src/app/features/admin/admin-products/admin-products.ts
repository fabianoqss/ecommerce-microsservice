import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, linkedSignal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { Page } from '../../../shared/data-access/product/page';
import { Product } from '../../../shared/data-access/product/product';
import { ProductApi } from '../../../shared/data-access/product/product-api';
import { ProductSearch } from '../../../shared/ui/product-search/product-search';
import { AdminProductCard } from '../admin-product-card/admin-product-card';

@Component({
  imports: [ProductSearch, AdminProductCard, Message, Skeleton],
  selector: 'app-admin-products',
  templateUrl: './admin-products.html',
})
export class AdminProducts {
  readonly name = input<string | string[]>();
  readonly category = input<string | string[]>();
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ProductApi);
  private readonly categoryResource = this.api.categories();
  protected readonly categories = computed(() =>
    this.categoryResource.hasValue() ? this.categoryResource.value() : [],
  );
  protected readonly filter = computed(() => {
    const name = this.name();
    const category = this.category();
    return {
      name: typeof name === 'string' ? name.trim().slice(0, 100) : '',
      category: typeof category === 'string' ? category.trim() : '',
    };
  });

  protected readonly products = this.api.list(() => ({ page: 0, ...this.filter() }), 4);
  protected readonly skeletons = [0, 1, 2, 3];
  protected readonly productPage = linkedSignal<
    Page<Product> | undefined,
    Page<Product> | undefined
  >({
    source: () => (this.products.hasValue() ? this.products.value() : undefined),
    computation: (value, previous) => value ?? previous?.value,
  });
  private readonly initialLoadFinished = linkedSignal({
    source: () => this.products.status(),
    computation: (status, previous) =>
      previous?.value === true || status === 'resolved' || status === 'error',
  });
  protected readonly initialLoading = computed(
    () => !this.initialLoadFinished() && this.products.isLoading(),
  );
  protected readonly hasFilters = computed(() => !!(this.filter().name || this.filter().category));
  protected readonly errorMessage = computed(() => {
    const error = this.products.error();
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
    return 'Não foi possível carregar os produtos. Tente novamente.';
  });
  protected readonly announcement = computed(() => {
    if (this.products.isLoading()) {
      return 'Carregando produtos.';
    }
    if (this.products.error()) {
      return this.errorMessage();
    }
    const total = this.productPage()?.totalElements ?? 0;
    return total === 1 ? '1 produto encontrado.' : `${total} produtos encontrados.`;
  });

  protected changeName(name: string): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { name: name || null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected changeCategory(category: string): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { category: category || null },
      queryParamsHandling: 'merge',
    });
  }

  protected clearFilters(): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { name: null, category: null },
      queryParamsHandling: 'merge',
    });
  }
}
