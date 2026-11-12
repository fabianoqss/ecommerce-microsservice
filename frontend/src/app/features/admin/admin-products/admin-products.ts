import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  linkedSignal,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ConfirmDialog } from 'primeng/confirmdialog';
import { finalize } from 'rxjs';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { InventoryApi } from '../../../shared/data-access/inventory/inventory-api';
import { Page } from '../../../shared/data-access/product/page';
import { Product } from '../../../shared/data-access/product/product';
import { ProductApi } from '../../../shared/data-access/product/product-api';
import { parseProductFilter } from '../../../shared/data-access/product/product-filter';
import { Pagination } from '../../../shared/ui/pagination/pagination';
import { ProductSearch } from '../../../shared/ui/product-search/product-search';
import { AdminProductCard } from '../admin-product-card/admin-product-card';
import { AdminProductApi } from '../data-access/admin-product-api';
import { problemDetail } from '../../../core/http/problem-detail';

@Component({
  imports: [
    ProductSearch,
    AdminProductCard,
    Message,
    Skeleton,
    RouterLink,
    ConfirmDialog,
    Pagination,
  ],
  selector: 'app-admin-products',
  templateUrl: './admin-products.html',
})
export class AdminProducts {
  readonly page = input<string | string[]>();
  readonly name = input<string | string[]>();
  readonly category = input<string | string[]>();
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ProductApi);
  private readonly inventoryApi = inject(InventoryApi);
  private readonly writeApi = inject(AdminProductApi);
  private readonly confirmation = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly scrollToResultsPending = signal(false);
  private readonly results = viewChild<ElementRef<HTMLElement>>('results');
  private readonly confirming = signal(false);
  private readonly deleting = signal(false);
  private readonly categoryResource = this.api.categories();
  protected readonly categories = computed(() =>
    this.categoryResource.hasValue() ? this.categoryResource.value() : [],
  );
  protected readonly filter = computed(() =>
    parseProductFilter({ page: this.page(), name: this.name(), category: this.category() }),
  );

  protected readonly products = this.api.list(this.filter, 4);
  protected readonly skeletons = [0, 1, 2, 3];
  private readonly resolvedPage = computed(() => {
    if (this.products.isLoading() || !this.products.hasValue()) return undefined;
    const result = this.products.value();
    return result.totalPages > 0 && this.filter().page >= result.totalPages ? undefined : result;
  });
  protected readonly inventory = this.inventoryApi.bySkus(
    () => this.resolvedPage()?.content.map((product) => product.id) ?? [],
  );
  protected readonly stockBySku = computed(() =>
    this.inventory.hasValue()
      ? new Map(this.inventory.value().map((item) => [item.skuCode, item.quantity]))
      : undefined,
  );
  protected readonly stockError = computed(() =>
    problemDetail(this.inventory.error(), 'Unable to load stock. Please try again.'),
  );
  protected readonly productPage = linkedSignal<
    Page<Product> | undefined,
    Page<Product> | undefined
  >({
    source: this.resolvedPage,
    computation: (value, previous) => value ?? previous?.value,
  });
  private readonly initialLoadFinished = linkedSignal({
    source: () => this.resolvedPage() !== undefined || this.products.status() === 'error',
    computation: (finished, previous) => previous?.value === true || finished,
  });
  protected readonly initialLoading = computed(() => !this.initialLoadFinished());
  protected readonly hasFilters = computed(() => !!(this.filter().name || this.filter().category));
  protected readonly errorMessage = computed(() =>
    problemDetail(this.products.error(), 'Unable to load products. Please try again.'),
  );
  protected readonly announcement = computed(() => {
    if (this.products.isLoading()) {
      return 'Loading products.';
    }
    if (this.products.error()) {
      return this.errorMessage();
    }
    const total = this.productPage()?.totalElements ?? 0;
    return total === 1 ? '1 product found.' : `${total} products found.`;
  });

  constructor() {
    effect(() => {
      if (this.products.error()) {
        untracked(() => this.scrollToResultsPending.set(false));
        return;
      }
      const rendered = this.resolvedPage() !== undefined;
      if (rendered && this.scrollToResultsPending()) {
        untracked(() => this.scrollToResultsPending.set(false));
        afterNextRender(() => this.results()?.nativeElement.scrollIntoView({ block: 'start' }), {
          injector: this.injector,
        });
      }
    });
    effect(() => {
      if (!this.products.hasValue() || this.products.isLoading()) return;
      const result = this.products.value();
      if (result.totalPages > 0 && this.filter().page >= result.totalPages) {
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { page: result.totalPages },
          queryParamsHandling: 'merge',
          replaceUrl: true,
        });
      }
    });
  }

  protected edit(product: Product): Promise<boolean> {
    return this.router.navigate(['/admin/products', product.id, 'edit'], {
      queryParamsHandling: 'preserve',
    });
  }

  protected remove(product: Product): void {
    if (this.confirming() || this.deleting()) return;
    this.confirming.set(true);
    let accepted = false;
    this.confirmation.confirm({
      header: 'Delete product',
      message: `Delete "${product.name}"? This action cannot be undone.`,
      acceptLabel: 'Delete',
      rejectLabel: 'Cancel',
      defaultFocus: 'reject',
      acceptButtonProps: { severity: 'danger' },
      rejectButtonProps: { severity: 'secondary', outlined: true },
      accept: () => {
        if (accepted || this.deleting()) return;
        accepted = true;
        this.confirming.set(false);
        this.deleting.set(true);
        this.writeApi
          .remove(product.id, product.version)
          .pipe(
            takeUntilDestroyed(this.destroyRef),
            finalize(() => this.deleting.set(false)),
          )
          .subscribe({
            next: () => {
              this.products.reload();
              this.messages.add({
                severity: 'success',
                summary: 'Product deleted',
                detail: 'Product deleted successfully.',
              });
            },
            error: (error: unknown) => {
              const status = error instanceof HttpErrorResponse ? error.status : undefined;
              const detail =
                status === 412
                  ? 'Someone else changed this product, so it was not deleted. The list will refresh; review the details before trying again.'
                  : status === 404
                    ? 'This product no longer exists. The list will refresh.'
                    : problemDetail(error, 'Unable to delete the product. Please try again.');
              this.messages.add({
                severity: status === 412 || status === 404 ? 'warn' : 'error',
                summary: 'Product not deleted',
                detail,
              });
              if (status === 412 || status === 404) this.products.reload();
            },
          });
      },
      reject: () => this.confirming.set(false),
    });
  }

  protected changeName(name: string): Promise<boolean> {
    this.scrollToResultsPending.set(false);
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { name: name || null, page: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected changeCategory(category: string): Promise<boolean> {
    this.scrollToResultsPending.set(false);
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { category: category || null, page: null },
      queryParamsHandling: 'merge',
    });
  }

  protected clearFilters(): Promise<boolean> {
    this.scrollToResultsPending.set(false);
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
