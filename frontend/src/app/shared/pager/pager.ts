import { DecimalPipe } from '@angular/common';
import { Component, computed, input, output } from '@angular/core';
import { PageMeta } from '../../core/models/models';

/** "Page 2 of 31 · 1,083 stops" with previous/next buttons. */
@Component({
  selector: 'app-pager',
  imports: [DecimalPipe],
  template: `
    @if (page(); as p) {
      <span class="muted">
        @if (p.totalPages > 1) {
          Page {{ p.number + 1 | number }} of {{ p.totalPages | number }} ·
        }
        {{ p.totalElements | number }} {{ noun() }}
      </span>
      @if (p.totalPages > 1) {
        <span class="buttons">
          <button type="button" [disabled]="p.number === 0" (click)="pageChange.emit(0)" aria-label="First page">
            «
          </button>
          <button type="button" [disabled]="p.number === 0" (click)="pageChange.emit(p.number - 1)">Previous</button>
          <button type="button" [disabled]="isLast()" (click)="pageChange.emit(p.number + 1)">Next</button>
          <button type="button" [disabled]="isLast()" (click)="pageChange.emit(p.totalPages - 1)" aria-label="Last page">
            »
          </button>
        </span>
      }
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem 1rem;
      padding: 0.75rem 0;
    }
    .buttons {
      display: flex;
      gap: 0.35rem;
    }
  `,
})
export class Pager {
  readonly page = input<PageMeta | null>(null);
  /** What is being counted, e.g. "stops". */
  readonly noun = input('rows');
  /** Zero-based page index. */
  readonly pageChange = output<number>();

  protected readonly isLast = computed(() => {
    const p = this.page();
    return !p || p.number >= p.totalPages - 1;
  });
}
