import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
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
import { httpsUrlValidator } from './https-url.validator';

type Field = 'name' | 'category' | 'price' | 'imageUrl' | 'description' | 'quantity';

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
      'Unable to load the product. Please try again.',
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
            : { validation: 'Enter a whole stock quantity between 0 and 2,147,483,647.' };
        },
      ],
    }),
    name: new FormControl('', {
      nonNullable: true,
      validators: [trimmedLength(1, 255, 'Enter a name with up to 255 characters.')],
    }),
    category: new FormControl('', {
      nonNullable: true,
      validators: [trimmedLength(1, 100, 'Enter a category with up to 100 characters.')],
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
                  'Enter a positive price up to 999,999,999,999.99. Use the format 1,234.56 with up to 2 decimal places.',
              };
        },
      ],
    }),
    imageUrl: new FormControl('', {
      nonNullable: true,
      validators: [httpsUrlValidator],
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [trimmedLength(10, 2000, 'Enter a description between 10 and 2000 characters.')],
    }),
  });
  private readonly imageUrlEvents = toSignal(this.form.controls.imageUrl.events);
  protected readonly imagePreviewUrl = computed(() => {
    this.imageUrlEvents();
    const control = this.form.controls.imageUrl;
    return control.valid ? control.value.trim() : '';
  });
  protected readonly imagePreviewFailed = linkedSignal({
    source: this.imagePreviewUrl,
    computation: () => false,
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
          imageUrl: product.imageUrl ?? '',
          description: product.description,
          price: new Intl.NumberFormat('en-US', {
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
      imageUrl: values.imageUrl.trim() || null,
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
                'The product was saved, but stock was not saved. ' +
                  problemDetail(error, 'Unable to save stock.') +
                  ' Try saving again to update the product and stock.',
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
            summary: 'Product saved',
            detail: wasEditing ? 'Product updated successfully.' : 'Product created successfully.',
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
                  field === 'imageUrl' ||
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
          this.saveError.set(problemDetail(error, 'Unable to save the product. Please try again.'));
        },
      });
  }
}
