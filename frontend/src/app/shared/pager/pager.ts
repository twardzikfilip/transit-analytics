import { Component, computed, input, output } from '@angular/core';
import { PageMeta } from '../../core/models/models';
import { LocalNumberPipe } from '../i18n/locale-pipes';
import { t, tn } from '../../core/i18n/t';

/** Number of the `i`-th row of a page, counted across pages (row 1 of page 2 of 50 is 51). */
export function rowNumber(page: PageMeta | null | undefined, i: number): number {
  return (page ? page.number * page.size : 0) + i + 1;
}

/** "Page 2 of 31 · 1,083 stops" with previous/next buttons. */
@Component({
  selector: 'app-pager',
  imports: [LocalNumberPipe],
  template: `
    @if (page(); as p) {
      <span class="muted">
        @if (p.totalPages > 1) {
          {{ t('pager.pageOf', { page: p.number + 1 | number, pages: p.totalPages | number }) }}
        }
        {{ tn(noun(), p.totalElements) }}
      </span>
      @if (p.totalPages > 1) {
        <span class="buttons">
          <button
            type="button"
            [disabled]="p.number === 0"
            (click)="pageChange.emit(0)"
            [attr.aria-label]="t('pager.first')"
          >
            «
          </button>
          <button type="button" [disabled]="p.number === 0" (click)="pageChange.emit(p.number - 1)">
            {{ t('pager.previousShort') }}
          </button>
          <button type="button" [disabled]="isLast()" (click)="pageChange.emit(p.number + 1)">
            {{ t('pager.nextShort') }}
          </button>
          <button
            type="button"
            [disabled]="isLast()"
            (click)="pageChange.emit(p.totalPages - 1)"
            [attr.aria-label]="t('pager.last')"
          >
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
  protected readonly t = t;
  protected readonly tn = tn;
  readonly page = input<PageMeta | null>(null);
  /** What is being counted: a plural translation key, e.g. 'pager.stops'. */
  readonly noun = input('pager.rows');
  /** Zero-based page index. */
  readonly pageChange = output<number>();

  protected readonly isLast = computed(() => {
    const p = this.page();
    return !p || p.number >= p.totalPages - 1;
  });
}
