import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ConfirmDialog } from 'primeng/confirmdialog';
import { finalize } from 'rxjs';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { Page } from '../../../shared/data-access/product/page';
import { Product } from '../../../shared/data-access/product/product';
import { ProductApi } from '../../../shared/data-access/product/product-api';
import { ProductSearch } from '../../../shared/ui/product-search/product-search';
import { AdminProductCard } from '../admin-product-card/admin-product-card';
import { AdminProductApi } from '../data-access/admin-product-api';
import { problemDetail } from '../data-access/problem-detail';

@Component({
  imports: [ProductSearch, AdminProductCard, Message, Skeleton, RouterLink, ConfirmDialog],
  selector: 'app-admin-products',
  templateUrl: './admin-products.html',
})
export class AdminProducts {
  readonly name = input<string | string[]>();
  readonly category = input<string | string[]>();
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ProductApi);
  private readonly writeApi = inject(AdminProductApi);
  private readonly confirmation = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly confirming = signal(false);
  private readonly deleting = signal(false);
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
  protected readonly errorMessage = computed(() =>
    problemDetail(this.products.error(), 'Não foi possível carregar os produtos. Tente novamente.'),
  );
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
      header: 'Excluir produto',
      message: `Excluir "${product.name}"? Esta ação não pode ser desfeita.`,
      acceptLabel: 'Excluir',
      rejectLabel: 'Cancelar',
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
                summary: 'Produto excluído',
                detail: 'Produto excluído com sucesso.',
              });
            },
            error: (error: unknown) => {
              const status = error instanceof HttpErrorResponse ? error.status : undefined;
              const detail =
                status === 412
                  ? 'O produto foi alterado por outra pessoa e não foi excluído. A lista será atualizada; confira os dados antes de tentar novamente.'
                  : status === 404
                    ? 'O produto não existe mais. A lista será atualizada.'
                    : problemDetail(error, 'Não foi possível excluir o produto. Tente novamente.');
              this.messages.add({
                severity: status === 412 || status === 404 ? 'warn' : 'error',
                summary: 'Exclusão não realizada',
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
