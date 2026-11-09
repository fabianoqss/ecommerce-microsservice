import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { catchError, EMPTY, finalize, switchMap, throwError } from 'rxjs';
import { InventoryApi } from '../../../shared/data-access/inventory/inventory-api';
import { Product } from '../../../shared/data-access/product/product';
import { ProductApi } from '../../../shared/data-access/product/product-api';
import { AdminProductApi, AdminProductRequest } from '../data-access/admin-product-api';
import { problemDetail, problemFields } from '../../../core/http/problem-detail';
import { parsePrice } from './parse-price';

type Field = 'name' | 'category' | 'price' | 'description' | 'quantity';

function trimmedLength(min: number, max: number, message: string): ValidatorFn {
  return (control) => {
    const value: unknown = control.value;
    const length = typeof value === 'string' ? value.trim().length : 0;
    return length >= min && length <= max ? null : { validation: message };
  };
}

@Component({
  selector: 'app-admin-product-form',
  imports: [ReactiveFormsModule, RouterLink, Message, Skeleton],
  templateUrl: './admin-product-form.html',
})
export class AdminProductForm {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(AdminProductApi);
  private readonly readApi = inject(ProductApi);
  private readonly inventoryApi = inject(InventoryApi);
  private readonly inventoryMissing = signal(false);
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });
  private readonly loadedProduct = signal<Product | undefined>(undefined);
  private readonly initialEditing = this.route.snapshot.paramMap.has('id');
  protected readonly editing = computed(() => this.initialEditing || !!this.loadedProduct());
  protected readonly productResource = this.initialEditing
    ? this.readApi.byId(() => this.params().get('id') ?? '')
    : undefined;
  protected readonly inventoryResource = this.inventoryApi.bySkus(() => {
    const id = this.params().get('id');
    return this.initialEditing && id ? [id] : [];
  });
  protected readonly loading = computed(
    () => !!this.productResource?.isLoading() || this.inventoryResource.isLoading(),
  );
  private readonly categoryResource = this.readApi.categories();
  protected readonly categories = computed(() =>
    this.categoryResource.hasValue() ? this.categoryResource.value() : [],
  );
  protected readonly saving = signal(false);
  protected readonly conflict = signal(false);
  protected readonly saveError = signal('');
  protected readonly notFound = computed(() => {
    const error = this.productResource?.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });
  protected readonly loadError = computed(() =>
    problemDetail(
      this.productResource?.error() ?? this.inventoryResource.error(),
      'Não foi possível carregar o produto. Tente novamente.',
    ),
  );
  protected readonly form = new FormGroup({
    quantity: new FormControl<number | null>(null, {
      validators: [
        (control) => {
          const value: unknown = control.value;
          return typeof value === 'number' &&
            Number.isInteger(value) &&
            value >= 0 &&
            value <= 2147483647
            ? null
            : { validation: 'Informe um estoque inteiro entre 0 e 2.147.483.647.' };
        },
      ],
    }),
    name: new FormControl('', {
      nonNullable: true,
      validators: [trimmedLength(1, 255, 'Informe um nome com até 255 caracteres.')],
    }),
    category: new FormControl('', {
      nonNullable: true,
      validators: [trimmedLength(1, 100, 'Informe uma categoria com até 100 caracteres.')],
    }),
    price: new FormControl('', {
      nonNullable: true,
      validators: [
        (control) => {
          const value: unknown = control.value;
          return typeof value === 'string' && parsePrice(value) !== null
            ? null
            : {
                validation:
                  'Informe um preço positivo até 999.999.999.999,99. Use o formato 1.234,56, com até 2 casas decimais.',
              };
        },
      ],
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [trimmedLength(10, 2000, 'Informe uma descrição entre 10 e 2000 caracteres.')],
    }),
  });

  constructor() {
    effect(() => {
      const resource = this.productResource;
      if (resource?.hasValue() && this.inventoryResource.hasValue() && !this.loading()) {
        const product = resource.value();
        const inventory = this.inventoryResource
          .value()
          .find((item) => item.skuCode === product.id);
        this.inventoryMissing.set(!inventory);
        this.loadedProduct.set(product);
        this.form.reset({
          quantity: inventory?.quantity ?? 0,
          name: product.name,
          category: product.category,
          description: product.description,
          price: new Intl.NumberFormat('pt-BR', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
          }).format(product.price),
        });
        this.conflict.set(false);
        this.saveError.set('');
      }
    });
  }

  protected fieldError(field: Field): string | null {
    const control = this.form.controls[field];
    if (!control.touched || !control.errors) return null;
    const message: unknown = control.errors['server'] ?? control.errors['validation'];
    return typeof message === 'string' ? message : null;
  }

  protected cancel(): Promise<boolean> {
    return this.router.navigate(['/admin/products'], { queryParamsHandling: 'preserve' });
  }

  protected reload(): void {
    if (this.productResource && this.conflict()) {
      this.productResource.reload();
      this.inventoryResource.reload();
    } else if (this.inventoryResource.error()) this.inventoryResource.reload();
    else if (this.productResource) this.productResource.reload();
    else {
      const product = this.loadedProduct();
      if (product) {
        void this.router.navigate(['/admin/products', product.id, 'edit'], {
          queryParamsHandling: 'preserve',
          replaceUrl: true,
        });
      }
    }
  }

  protected save(): void {
    if (this.saving() || this.conflict() || this.loading() || this.inventoryResource.error())
      return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const values = this.form.getRawValue();
    const price = parsePrice(values.price);
    const product = this.loadedProduct();
    const quantity = values.quantity;
    if (price === null || quantity === null || (this.editing() && !product)) return;
    const body: AdminProductRequest = {
      name: values.name.trim(),
      category: values.category.trim(),
      description: values.description.trim(),
      price,
      imageUrl: product?.imageUrl ?? null,
    };
    this.saving.set(true);
    this.saveError.set('');
    const createInventory = !product || this.inventoryMissing();
    const wasEditing = this.editing();
    const request = product
      ? this.api.update(product.id, product.version, body)
      : this.api.create(body);
    request
      .pipe(
        switchMap((savedProduct) => {
          this.loadedProduct.set(savedProduct);
          this.inventoryMissing.set(false);
          const stock = createInventory
            ? this.inventoryApi.create({ skuCode: savedProduct.id, quantity })
            : this.inventoryApi
                .update(savedProduct.id, { quantity })
                .pipe(
                  catchError((error: unknown) =>
                    error instanceof HttpErrorResponse && error.status === 404
                      ? this.inventoryApi.create({ skuCode: savedProduct.id, quantity })
                      : throwError(() => error),
                  ),
                );
          return stock.pipe(
            catchError((error: unknown) => {
              this.saveError.set(
                'O produto foi salvo, mas o estoque não foi salvo. ' +
                  problemDetail(error, 'Não foi possível salvar o estoque.') +
                  ' Tente salvar novamente para atualizar o produto e o estoque.',
              );
              return EMPTY;
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: () => {
          this.messages.add({
            severity: 'success',
            summary: 'Produto salvo',
            detail: wasEditing ? 'Produto atualizado com sucesso.' : 'Produto criado com sucesso.',
          });
          void this.cancel();
        },
        error: (error: unknown) => {
          if (error instanceof HttpErrorResponse && error.status === 412) {
            this.conflict.set(true);
            return;
          }
          if (error instanceof HttpErrorResponse && error.status === 400) {
            const fields = Object.entries(problemFields(error));
            let unmapped = fields.length === 0;
            for (const [field, message] of fields) {
              if (
                (field === 'name' ||
                  field === 'category' ||
                  field === 'price' ||
                  field === 'description') &&
                typeof message === 'string' &&
                message.trim()
              ) {
                this.form.controls[field].setErrors({ server: message });
              } else {
                unmapped = true;
              }
            }
            if (!unmapped) return;
          }
          this.saveError.set(
            problemDetail(error, 'Não foi possível salvar o produto. Tente novamente.'),
          );
        },
      });
  }
}
