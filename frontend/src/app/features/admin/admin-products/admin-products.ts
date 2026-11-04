import { Component, computed, inject, input } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ProductApi } from '../../../shared/data-access/product/product-api';
import { ProductSearch } from '../../../shared/ui/product-search/product-search';

@Component({
  imports: [ProductSearch],
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
