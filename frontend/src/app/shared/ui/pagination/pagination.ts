import { Component, computed, inject, input, output } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { pageWindow } from './page-window';

@Component({
  imports: [],
  selector: 'app-pagination',
  templateUrl: './pagination.html',
})
export class Pagination {
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly pageChange = output<number>();
  private readonly breakpoints = toSignal(
    inject(BreakpointObserver).observe(['(min-width: 768px)', '(min-width: 480px)']),
  );
  protected readonly pages = computed(() => {
    const breakpoints = this.breakpoints()?.breakpoints;
    const maximum = breakpoints?.['(min-width: 768px)']
      ? 10
      : breakpoints?.['(min-width: 480px)']
        ? 6
        : 4;
    return pageWindow(this.page(), this.totalPages(), maximum);
  });
}
