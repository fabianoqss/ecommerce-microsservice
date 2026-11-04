import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';
import { finalize } from 'rxjs';
import { Product } from '../../../shared/data-access/product/product';
import { ProductApi } from '../../../shared/data-access/product/product-api';
import { AdminProductApi, AdminProductRequest } from '../data-access/admin-product-api';
import { problemDetail, problemFields } from '../data-access/problem-detail';
import { parsePrice } from './parse-price';

type Field = 'name' | 'category' | 'price' | 'description';

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
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });
  private readonly loadedProduct = signal<Product | undefined>(undefined);
  protected readonly editing = this.route.snapshot.paramMap.has('id');
  protected readonly productResource = this.editing
    ? this.readApi.byId(() => this.params().get('id') ?? '')
    : undefined;
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
      this.productResource?.error(),
      'Não foi possível carregar o produto. Tente novamente.',
    ),
  );
  protected readonly form = new FormGroup({
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
      if (resource?.hasValue() && !resource.isLoading()) {
        const product = resource.value();
        this.loadedProduct.set(product);
        this.form.reset({
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
    this.productResource?.reload();
  }

  protected save(): void {
    if (this.saving() || this.conflict() || this.productResource?.isLoading()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const values = this.form.getRawValue();
    const price = parsePrice(values.price);
    const product = this.loadedProduct();
    if (price === null || (this.editing && !product)) return;
    const body: AdminProductRequest = {
      name: values.name.trim(),
      category: values.category.trim(),
      description: values.description.trim(),
      price,
      imageUrl: product?.imageUrl ?? null,
    };
    this.saving.set(true);
    this.saveError.set('');
    const request = product
      ? this.api.update(product.id, product.version, body)
      : this.api.create(body);
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: () => {
          this.messages.add({
            severity: 'success',
            summary: 'Produto salvo',
            detail: this.editing
              ? 'Produto atualizado com sucesso.'
              : 'Produto criado com sucesso.',
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
