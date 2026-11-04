import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { Pagination } from '../../../shared/ui/pagination/pagination';
import { ProductApi } from '../../../shared/data-access/product/product-api';
import { ProductFilter } from '../../../shared/data-access/product/product-filter';
import { ProductCard } from '../product-card/product-card';
import { ProductSearch } from '../../../shared/ui/product-search/product-search';

@Component({
  imports: [Message, Skeleton, Pagination, ProductCard, ProductSearch],
  selector: 'app-product-list',
  templateUrl: './product-list.html',
})
export class ProductList {
  readonly page = input<string | string[]>();
  readonly name = input<string | string[]>();
  readonly category = input<string | string[]>();
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ProductApi);
  private readonly injector = inject(Injector);
  private readonly scrollToResultsPending = signal(false);
  private readonly results = viewChild<ElementRef<HTMLElement>>('results');
  protected readonly skeletons = Array.from({ length: 8 }, (_, index) => index);
  protected readonly filter = computed<ProductFilter>(() => {
    const rawPage = this.page() ?? '1';
    const page = typeof rawPage === 'string' && /^\d+$/.test(rawPage) ? Number(rawPage) : 1;
    const name = this.name();
    const category = this.category();
    return {
      page: Number.isSafeInteger(page) && page > 0 && page <= 2147483647 ? page - 1 : 0,
      name: typeof name === 'string' ? name.trim().slice(0, 100) : '',
      category: typeof category === 'string' ? category.trim() : '',
    };
  });
  protected readonly products = this.api.list(this.filter);
  private readonly categoryResource = this.api.categories();
  protected readonly categories = computed(() =>
    this.categoryResource.hasValue() ? this.categoryResource.value() : [],
  );
  protected readonly productPage = computed(() =>
    this.products.hasValue() ? this.products.value() : undefined,
  );
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

  constructor() {
    effect(() => {
      const rendered = this.productPage() !== undefined && !this.products.isLoading();
      if (rendered && this.scrollToResultsPending()) {
        untracked(() => this.scrollToResultsPending.set(false));
        afterNextRender(() => this.results()?.nativeElement.scrollIntoView({ block: 'start' }), {
          injector: this.injector,
        });
      }
    });
    effect(() => {
      const result = this.productPage();
      if (result && result.totalPages > 0 && this.filter().page >= result.totalPages) {
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { page: result.totalPages },
          queryParamsHandling: 'merge',
          replaceUrl: true,
        });
      }
    });
  }

  protected changeName(name: string): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { name: name || null, page: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected changeCategory(category: string): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { category: category || null, page: null },
      queryParamsHandling: 'merge',
    });
  }

  protected clearFilters(): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { name: null, category: null, page: null },
      queryParamsHandling: 'merge',
    });
  }

  protected async changePage(page: number): Promise<void> {
    if (page === this.filter().page || page < 0 || page >= (this.productPage()?.totalPages ?? 0)) {
      return;
    }
    const navigated = await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { page: page + 1 },
      queryParamsHandling: 'merge',
    });
    if (navigated) {
      this.scrollToResultsPending.set(true);
    }
  }
}
