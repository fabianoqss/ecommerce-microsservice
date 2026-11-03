import { Component, effect, input, output } from '@angular/core';
import { outputFromObservable } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Subject, map, switchMap, takeUntil, timer } from 'rxjs';

@Component({
  imports: [ReactiveFormsModule],
  selector: 'app-product-search',
  templateUrl: './product-search.html',
})
export class ProductSearch {
  readonly name = input('');
  readonly category = input('');
  readonly categories = input<string[]>([]);
  readonly clear = output<void>();
  protected readonly nameControl = new FormControl('', { nonNullable: true });
  protected readonly categoryControl = new FormControl('', { nonNullable: true });
  private readonly cancelNameChange = new Subject<void>();
  readonly nameChange = outputFromObservable(
    this.nameControl.valueChanges.pipe(
      switchMap((name) =>
        timer(300).pipe(
          takeUntil(this.cancelNameChange),
          map(() => name.trim().slice(0, 100)),
        ),
      ),
    ),
  );
  readonly categoryChange = outputFromObservable(this.categoryControl.valueChanges);

  constructor() {
    effect(() => {
      this.cancelNameChange.next();
      this.nameControl.setValue(this.name(), { emitEvent: false });
    });
    effect(() => {
      this.categoryControl.setValue(this.category(), { emitEvent: false });
    });
    effect(() => {
      if (this.categories().length) {
        this.categoryControl.enable({ emitEvent: false });
      } else {
        this.categoryControl.disable({ emitEvent: false });
      }
    });
  }

  clearFilters(): void {
    this.cancelNameChange.next();
    this.nameControl.setValue('', { emitEvent: false });
    this.categoryControl.setValue('', { emitEvent: false });
    this.clear.emit();
  }
}
